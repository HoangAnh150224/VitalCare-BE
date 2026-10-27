package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.billing.Vnd;
import com.vn.vitalcare.care.billing.catalog.MedicalServiceCatalog;
import com.vn.vitalcare.care.billing.vnpay.VnpayClient;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.serviceorder.ServiceOrderRepository;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Invoice;
import com.vn.vitalcare.entity.InvoiceItem;
import com.vn.vitalcare.entity.InvoiceStatus;
import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.Payment;
import com.vn.vitalcare.entity.PaymentMethod;
import com.vn.vitalcare.entity.PaymentStatus;
import com.vn.vitalcare.entity.ServiceCategory;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.entity.ServiceOrderStatus;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bills and their payments (Report 1 FE-23): one bill per concluded
 * encounter, paid once — at the counter, or online through VNPay — and the
 * clinic's revenue over a period.
 *
 * <p>Responses are built here, inside the transaction, because a bill's lines
 * and payments are two collections no single query can fetch together.
 */
@Service
@Transactional(readOnly = true)
public class Invoices {

    private static final Logger log = LoggerFactory.getLogger(Invoices.class);
    /** How long a VNPay payment URL stays usable (VnpayClient sets vnp_ExpireDate to 15 minutes). */
    private static final java.time.Duration VNPAY_WINDOW = java.time.Duration.ofMinutes(15);

    private static final Set<String> SORTABLE = Set.of("id", "issuedAt", "status", "totalAmount");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("issuedAt"), Sort.Order.desc("id"));
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** The VNPay answer that means the money moved; anything else did not. */
    private static final String VNPAY_OK = "00";

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ServiceOrderRepository orders;
    private final MedicalServiceCatalog catalog;
    private final AppointmentService appointmentService;
    private final CustomerService customerService;
    private final ClinicService clinicService;
    private final ClinicScope clinicScope;
    private final VnpayClient vnpay;
    private final RowLevelSecurity rowLevel;
    private final EntityManager entityManager;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public Invoices(InvoiceRepository invoices,
                    PaymentRepository payments,
                    ServiceOrderRepository orders,
                    MedicalServiceCatalog catalog,
                    AppointmentService appointmentService,
                    CustomerService customerService,
                    ClinicService clinicService,
                    ClinicScope clinicScope,
                    VnpayClient vnpay,
                    RowLevelSecurity rowLevel,
                    EntityManager entityManager,
                    Clock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.orders = orders;
        this.catalog = catalog;
        this.appointmentService = appointmentService;
        this.customerService = customerService;
        this.clinicService = clinicService;
        this.clinicScope = clinicScope;
        this.vnpay = vnpay;
        this.rowLevel = rowLevel;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // --- issuing ------------------------------------------------------------------

    /**
     * The bill for an encounter being concluded: the examination fee, then
     * every service ordered in it that was not cancelled, at the price it was
     * ordered at. Called by the encounter's completion, in its transaction;
     * an encounter already billed keeps its bill.
     */
    @Transactional
    public Invoice issueFor(ClinicalEncounter encounter) {
        var existing = invoices.findByEncounter(encounter.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        MedicalService consultation = catalog.consultation();

        Invoice invoice = new Invoice();
        invoice.setCustomer(encounter.getCustomer());
        invoice.setEncounter(encounter);
        invoice.setAppointment(encounter.getAppointment());
        invoice.setClinic(encounter.getClinic());
        invoice.setIssuedAt(now);
        invoice.addItem(new InvoiceItem(consultation, consultation.getName(), 1, consultation.getPrice()));
        for (ServiceOrder order : orders.findByEncounter(encounter.getId())) {
            if (order.getStatus() != ServiceOrderStatus.CANCELLED) {
                invoice.addItem(new InvoiceItem(order.getService(), order.getService().getName(), 1,
                        order.getUnitPrice()));
            }
        }
        // A provisional number the unique column accepts, replaced by the real one once the id exists.
        invoice.setInvoiceNumber("HD-TMP-" + UUID.randomUUID());
        Invoice saved = invoices.persist(invoice);
        invoices.flush();
        saved.setInvoiceNumber("HD-%s-%05d".formatted(now.atZoneSameInstant(zone()).format(DAY), saved.getId()));
        return invoices.persist(saved);
    }

    // --- staff --------------------------------------------------------------------

    public Page<InvoiceResponse> list(ListParams params) {
        return invoices.findAll(rowLevel.scope(Invoice.class, Action.READ).and(specification(params)),
                        params.pageable(SORTABLE, DEFAULT_SORT))
                .map(invoice -> InvoiceResponse.from(invoice, rowLevel.capabilities(invoice)));
    }

    public InvoiceResponse get(Long id) {
        Invoice invoice = find(id, Action.READ);
        return InvoiceResponse.from(invoice, rowLevel.capabilities(invoice));
    }

    /**
     * Cash or a transfer taken at the counter: the bill is settled now. The
     * bill is locked first, so two clerks pressing at once settle it once and
     * the second is told it is already paid.
     */
    @Transactional
    public InvoiceResponse payAtCounter(Long id, PaymentMethod method) {
        if (method != PaymentMethod.CASH && method != PaymentMethod.BANK_TRANSFER) {
            throw new FieldValidationException("method", "At the counter: cash or bank transfer");
        }
        find(id, Action.WRITE);
        Invoice invoice = lock(id);
        if (invoice.getStatus() != InvoiceStatus.UNPAID) {
            throw new ConflictException("This bill is already settled");
        }
        // The patient may be on VNPay's page right now; taking cash too would charge them twice.
        java.time.Instant since = clock.instant().minus(VNPAY_WINDOW);
        boolean payingOnline = invoice.getPayments().stream().anyMatch(p -> p.getMethod() == PaymentMethod.VNPAY
                && p.getStatus() == PaymentStatus.PENDING && p.getCreatedAt() != null && p.getCreatedAt().isAfter(since));
        if (payingOnline) {
            throw new ConflictException("The patient started paying online in the last 15 minutes; "
                    + "wait for it to finish or expire before taking payment at the counter");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Payment payment = new Payment(invoice, invoice.getTotalAmount(), method, "QUAY-" + invoice.getId() + "-" + suffix());
        payment.succeed(now, null, null);
        invoice.getPayments().add(payments.save(payment));
        invoice.markPaid(now);
        rowLevel.checkWritable(invoice);
        invoices.persist(invoice);
        return InvoiceResponse.from(invoice, rowLevel.capabilities(invoice));
    }

    /**
     * Revenue over a period at the caller's clinic, by the day bills were
     * issued: the examination and service parts of what was paid, and what is
     * still owed.
     */
    public RevenueReport report(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new FieldValidationException("to", "The end date is before the start");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw new FieldValidationException("to", "A report covers at most a year");
        }
        UUID clinicId = clinicScope.currentClinicId()
                .orElseThrow(() -> new ConflictException("The revenue report is a clinic's; your account has no clinic"));
        ZoneId zone = zone();
        List<Invoice> issued = invoices.findIssuedBetween(clinicId,
                from.atStartOfDay(zone).toOffsetDateTime(), to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());

        Map<LocalDate, RevenueReport.Day> days = new TreeMap<>();
        RevenueReport.Day total = RevenueReport.Day.empty(null);
        for (Invoice invoice : issued) {
            LocalDate day = invoice.getIssuedAt().atZoneSameInstant(zone).toLocalDate();
            BigDecimal consultation = BigDecimal.ZERO;
            BigDecimal services = BigDecimal.ZERO;
            for (InvoiceItem item : invoice.getItems()) {
                boolean exam = item.getService() != null && item.getService().getCategory() == ServiceCategory.CONSULTATION;
                if (exam) {
                    consultation = consultation.add(item.getAmount());
                } else {
                    services = services.add(item.getAmount());
                }
            }
            boolean paid = invoice.getStatus() == InvoiceStatus.PAID;
            RevenueReport.Day row = RevenueReport.Day.of(day, paid, consultation, services, invoice.getTotalAmount());
            days.merge(day, row, RevenueReport.Day::plus);
            total = total.plus(row);
        }
        return new RevenueReport(clinicId, from, to, total.withDate(null), new ArrayList<>(days.values()));
    }

    // --- the customer's own -------------------------------------------------------

    /** The bill for one of the caller's own appointments; none yet answers as not found. */
    public InvoiceResponse ownForAppointment(Long appointmentId) {
        Invoice invoice = ownInvoice(appointmentId);
        return InvoiceResponse.from(invoice, Map.of());
    }

    public boolean onlinePaymentEnabled() {
        return vnpay.enabled();
    }

    /**
     * Starts paying the caller's own bill through VNPay: a pending attempt
     * with a fresh reference, and the signed URL to send them to.
     */
    @Transactional
    public String startVnpay(Long appointmentId, String clientIp) {
        if (!vnpay.enabled()) {
            throw new ConflictException("Online payment is not set up for this clinic yet");
        }
        Invoice owned = ownInvoice(appointmentId);
        Invoice invoice = lock(owned.getId());
        if (invoice.getStatus() != InvoiceStatus.UNPAID) {
            throw new ConflictException("This bill is already settled");
        }
        String txnRef = invoice.getId() + "-" + suffix();
        payments.save(new Payment(invoice, invoice.getTotalAmount(), PaymentMethod.VNPAY, txnRef));
        return vnpay.paymentUrl(txnRef, Vnd.of(invoice.getTotalAmount()),
                "Thanh toan hoa don " + invoice.getInvoiceNumber(), clientIp);
    }

    /**
     * What VNPay told us about an attempt, from the customer's return or its
     * server-to-server IPN — whichever arrives first settles it, the other
     * finds it settled. Not scoped: the signature is the authority here.
     */
    @Transactional
    public Settlement settleVnpay(Map<String, String> params) {
        if (!vnpay.verify(params)) {
            return Settlement.of(Settlement.Outcome.BAD_SIGNATURE, null);
        }
        var found = payments.findByTransactionCodeForUpdate(params.getOrDefault("vnp_TxnRef", ""));
        if (found.isEmpty()) {
            return Settlement.of(Settlement.Outcome.NOT_FOUND, null);
        }
        Payment payment = found.get();
        Invoice invoice = payment.getInvoice();
        Long appointmentId = invoice.getAppointment() == null ? null : invoice.getAppointment().getId();
        BigDecimal amount;
        try {
            amount = new BigDecimal(params.getOrDefault("vnp_Amount", "")).movePointLeft(2);
        } catch (NumberFormatException e) {
            return Settlement.of(Settlement.Outcome.BAD_AMOUNT, appointmentId);
        }
        if (amount.compareTo(Vnd.of(payment.getAmount())) != 0) {
            return Settlement.of(Settlement.Outcome.BAD_AMOUNT, appointmentId);
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return Settlement.of(payment.getStatus() == PaymentStatus.PAID
                    ? Settlement.Outcome.ALREADY_PAID : Settlement.Outcome.ALREADY_FAILED, appointmentId);
        }

        String code = params.get("vnp_ResponseCode");
        String providerRef = params.get("vnp_TransactionNo");
        boolean ok = VNPAY_OK.equals(code) && VNPAY_OK.equals(params.get("vnp_TransactionStatus"));
        if (!ok) {
            payment.fail(code, providerRef);
            payments.save(payment);
            return Settlement.of(Settlement.Outcome.FAILED, appointmentId);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        payment.succeed(now, code, providerRef);
        payments.save(payment);
        Invoice locked = lock(invoice.getId());
        if (locked.getStatus() == InvoiceStatus.UNPAID) {
            locked.markPaid(now);
            invoices.persist(locked);
        } else {
            // Money moved for a bill already settled (a second tab, an expired-looking URL paid
            // anyway). Recorded as what happened, and said loudly so somebody refunds it.
            log.warn("VNPay payment {} succeeded on invoice {} that was already {}: refund needed",
                    payment.getTransactionCode(), locked.getInvoiceNumber(), locked.getStatus());
        }
        // ponytail: two VNPay tabs can still both succeed (only the counter waits
        // for a pending attempt); the second is logged above and refunded by hand.
        // Void older attempts through VNPay's querydr/refund API if that ever matters.
        return Settlement.of(Settlement.Outcome.PAID, appointmentId);
    }

    /** What a gateway answer came to, and the appointment to send the customer back to. */
    public record Settlement(Outcome outcome, Long appointmentId) {

        public enum Outcome { PAID, FAILED, ALREADY_PAID, ALREADY_FAILED, NOT_FOUND, BAD_AMOUNT, BAD_SIGNATURE }

        static Settlement of(Outcome outcome, Long appointmentId) {
            return new Settlement(outcome, appointmentId);
        }
    }

    // --- helpers ------------------------------------------------------------------

    private Invoice ownInvoice(Long appointmentId) {
        Customer customer = customerService.getForCurrentUser();
        Appointment appointment = appointmentService.getOwn(customer, appointmentId);
        return invoices.findByAppointment(appointment.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice for appointment", appointmentId));
    }

    /**
     * The bill, locked, then re-read: a copy this transaction already holds
     * (read to check access) would otherwise hide a payment another
     * transaction committed while this one waited for the lock.
     */
    private Invoice lock(Long id) {
        Invoice invoice = invoices.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Invoice", id));
        entityManager.refresh(invoice);
        return invoice;
    }

    private Invoice find(Long id, Action action) {
        return invoices.findOne(Specification.allOf(
                        rowLevel.byId(Invoice.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", id));
    }

    private ZoneId zone() {
        return clinicService.timeZone();
    }

    /** Six random digits: a reference two attempts on one bill cannot share. */
    private String suffix() {
        return "%06d".formatted(random.nextInt(1_000_000));
    }

    private Specification<Invoice> specification(ListParams params) {
        List<Specification<Invoice>> specs = new ArrayList<>();
        specs.add(BaseEntitySpecifications.notDeleted());
        ZoneId zone = zone();
        for (ListParams.Criterion criterion : params.filters()) {
            try {
                switch (criterion.field()) {
                    case "status" -> {
                        InvoiceStatus status = InvoiceStatus.from(criterion.value());
                        if (status != null) {
                            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
                        }
                    }
                    // The bill of one visit: what the front desk opens on the visit's page to take the money.
                    case "appointmentId", "appointment.id" -> {
                        Long appointmentId = Long.valueOf(criterion.value().trim());
                        specs.add((root, query, cb) -> cb.equal(root.get("appointment").get("id"), appointmentId));
                    }
                    // issuedAt_gte / issuedAt_lte: whole days in the clinic's zone.
                    case "issuedAt" -> {
                        LocalDate day = LocalDate.parse(criterion.value().trim().substring(0, 10));
                        OffsetDateTime start = day.atStartOfDay(zone).toOffsetDateTime();
                        OffsetDateTime end = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
                        specs.add((root, query, cb) -> switch (criterion.operator()) {
                            case GTE -> cb.greaterThanOrEqualTo(root.get("issuedAt"), start);
                            case LTE -> cb.lessThan(root.get("issuedAt"), end);
                            default -> cb.and(cb.greaterThanOrEqualTo(root.get("issuedAt"), start),
                                    cb.lessThan(root.get("issuedAt"), end));
                        });
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException | DateTimeParseException | StringIndexOutOfBoundsException e) {
                specs.add((root, query, cb) -> cb.disjunction());
            }
        }
        params.search().ifPresent(term -> specs.add((root, query, cb) -> {
            var customer = root.join("customer");
            var account = customer.join("user", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(root.get("invoiceNumber")), ListParams.likePattern(term)),
                    cb.like(cb.lower(customer.get("fullName")), ListParams.likePattern(term)),
                    cb.like(cb.lower(account.get("fullName")), ListParams.likePattern(term)));
        }));
        return Specification.allOf(specs);
    }
}
