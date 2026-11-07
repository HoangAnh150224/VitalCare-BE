package com.vn.vitalcare.care.billing.invoice;

import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.appointment.service.VisitEvents;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
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
import com.vn.vitalcare.entity.InvoiceKind;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bills and their payments (Report 1 FE-23): a visit's examination bill,
 * issued when it is registered and paid before it queues (BF-02), and one bill
 * per ordered service, paid before the service is done (BF-03). Each is paid
 * once — at the counter, by transfer, or online through VNPay — and the
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
    private final ApplicationEventPublisher events;
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
                    ApplicationEventPublisher events,
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
        this.events = events;
        this.clock = clock;
    }

    // --- issuing ------------------------------------------------------------------

    /** BF-02: a visit just registered is billed its examination fee, to be paid before it queues. */
    @EventListener
    @Transactional
    public void onRegistered(VisitEvents.Registered event) {
        Appointment appointment = entityManager.find(Appointment.class, event.appointmentId());
        if (appointment != null) {
            issueExam(appointment);
        }
    }

    /**
     * About to cancel a visit: its examination bill is locked first, so the
     * cancellation takes the bill then the visit — the same order as a payment
     * settling it — and the two wait for each other instead of deadlocking.
     */
    @EventListener
    @Transactional
    public void onCancelling(VisitEvents.Cancelling event) {
        invoices.findExamOf(event.appointmentId()).ifPresent(found -> lock(found.getId()));
    }

    /** A visit called off before it was seen: its examination bill too, unless already paid. */
    @EventListener
    @Transactional
    public void onCancelled(VisitEvents.Cancelled event) {
        invoices.findExamOf(event.appointmentId()).ifPresent(found -> {
            Invoice invoice = lock(found.getId());
            if (invoice.getStatus() == InvoiceStatus.UNPAID) {
                invoice.cancel();
                invoices.persist(invoice);
            } else if (invoice.getStatus() == InvoiceStatus.PAID
                    && invoice.getAppointment() != null
                    && invoice.getAppointment().getStatus() == com.vn.vitalcare.entity.AppointmentStatus.NO_SHOW) {
                // UC-02 B1: a missed visit's paid fee pays for the walk-in the patient may still come for.
                log.info("Visit {} was missed with its examination bill {} paid: kept for a walk-in",
                        event.appointmentId(), invoice.getInvoiceNumber());
            } else if (invoice.getStatus() == InvoiceStatus.PAID) {
                log.warn("Visit {} was cancelled after its examination bill {} was paid: refund needed",
                        event.appointmentId(), invoice.getInvoiceNumber());
            }
        });
    }

    /**
     * UC-02 B1: a visit the patient missed or that was cancelled, replaced by a
     * walk-in today. An examination fee already paid on it pays for the new
     * one: the paid bill moves over, the new visit's own fresh bill is called
     * off, and the new visit is paid now — so it queues from now (BR-05).
     * Nothing changes when the old fee was not paid.
     *
     * @return whether a paid fee was carried over
     */
    @Transactional
    public boolean carryExamPaid(Long fromAppointmentId, Appointment to) {
        var found = invoices.findExamOf(fromAppointmentId);
        if (found.isEmpty()) {
            return false;
        }
        // The bills before the visit, the order every payment and cancellation takes them in.
        Invoice paid = lock(found.get().getId());
        if (paid.getStatus() != InvoiceStatus.PAID) {
            return false;
        }
        invoices.findExamOf(to.getId()).ifPresent(fresh -> {
            Invoice unpaid = lock(fresh.getId());
            if (unpaid.getStatus() == InvoiceStatus.UNPAID) {
                unpaid.cancel();
                invoices.persist(unpaid);
            }
        });
        // Written now: a visit holds one live exam bill (uq_invoice_exam_appointment), and Hibernate
        // would otherwise move the paid one over before calling the fresh one off.
        entityManager.flush();
        paid.setAppointment(to);
        invoices.persist(paid);
        appointmentService.markExamPaid(to.getId(), OffsetDateTime.now(clock));
        return true;
    }

    /** A visit moved to another doctor, maybe of another clinic: an unpaid bill moves with it. */
    @EventListener
    @Transactional
    public void onRescheduled(VisitEvents.Rescheduled event) {
        invoices.findExamOf(event.appointmentId()).ifPresent(found -> {
            Invoice invoice = lock(found.getId());
            Appointment appointment = invoice.getAppointment();
            if (invoice.getStatus() != InvoiceStatus.UNPAID || appointment == null) {
                return;
            }
            boolean changed = false;
            if (!appointment.getClinicId().equals(invoice.getClinicId())) {
                invoice.setClinic(appointment.getClinic());
                changed = true;
            }
            // Given to another profile of the account at the desk (UC-02 B1).
            if (!appointment.getCustomer().getId().equals(invoice.getCustomer().getId())) {
                invoice.setCustomer(appointment.getCustomer());
                changed = true;
            }
            if (changed) {
                invoices.persist(invoice);
            }
        });
    }

    /**
     * Bills an encounter concluded under the rule before bills came first: a
     * visit already past the desk when that changed has no examination bill,
     * and its services none of their own. Issued now, as the visit used to be
     * billed at its end; a visit billed the new way gets nothing more. An
     * encounter with no registered visit is billed its examination here too.
     */
    @Transactional
    public void billLegacyVisit(ClinicalEncounter encounter) {
        Appointment appointment = encounter.getAppointment();
        if (appointment != null && appointment.getReferredFrom() != null) {
            return;
        }
        boolean hasExam = appointment != null
                ? invoices.findExamOf(appointment.getId()).isPresent()
                : invoices.findAllOfEncounter(encounter.getId()).stream().anyMatch(i -> i.getKind() == InvoiceKind.EXAM);
        if (!hasExam) {
            MedicalService consultation = catalog.consultation();
            Invoice invoice = newInvoice(InvoiceKind.EXAM, encounter.getCustomer(), appointment, encounter.getClinic());
            invoice.setEncounter(encounter);
            invoice.addItem(new InvoiceItem(consultation, consultation.getName(), 1, consultation.getPrice()));
            number(invoice);
        }
        for (ServiceOrder order : orders.findByEncounter(encounter.getId())) {
            if (order.getStatus() != ServiceOrderStatus.CANCELLED && invoices.findForOrder(order.getId()).isEmpty()
                    && invoices.findAnyForOrder(order.getId()).isEmpty()) {
                issueForOrder(order);
            }
        }
    }

    /**
     * A visit's examination bill: the fee from the catalogue. A visit that has
     * one keeps it, so registering, the desk and the portal can all ask.
     */
    @Transactional
    public Invoice issueExam(Appointment appointment) {
        var existing = invoices.findExamOf(appointment.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        MedicalService consultation = catalog.consultation();
        Invoice invoice = newInvoice(InvoiceKind.EXAM, appointment.getCustomer(), appointment, appointment.getClinic());
        invoice.addItem(new InvoiceItem(consultation, consultation.getName(), 1, consultation.getPrice()));
        return number(invoice);
    }

    /** BF-03: an ordered service is billed on its own, at the price it was ordered at, and done once paid. */
    @Transactional
    public Invoice issueForOrder(ServiceOrder order) {
        var existing = invoices.findForOrder(order.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        ClinicalEncounter encounter = order.getEncounter();
        Invoice invoice = newInvoice(InvoiceKind.SERVICE, order.getCustomer(), encounter.getAppointment(),
                encounter.getClinic());
        invoice.setEncounter(encounter);
        invoice.setServiceOrder(order);
        invoice.addItem(new InvoiceItem(order.getService(), order.getService().getName(), 1, order.getUnitPrice()));
        return number(invoice);
    }

    /** A cancelled service's bill is called off; one already paid is kept and flagged for a refund. */
    @Transactional
    public void cancelForOrder(ServiceOrder order) {
        invoices.findForOrder(order.getId()).ifPresent(found -> {
            Invoice invoice = lock(found.getId());
            if (invoice.getStatus() == InvoiceStatus.UNPAID) {
                invoice.cancel();
                invoices.persist(invoice);
            } else {
                log.warn("Service order {} was cancelled after its bill {} was paid: refund needed",
                        order.getId(), invoice.getInvoiceNumber());
            }
        });
    }

    /**
     * Whether an ordered service may be carried out: its bill is paid. An
     * order from before services were billed one by one has no bill of its
     * own; it was paid for with the visit, and may go ahead.
     */
    public boolean orderPaid(ServiceOrder order) {
        return invoices.findForOrder(order.getId())
                .map(invoice -> invoice.getStatus() == InvoiceStatus.PAID)
                .orElse(true);
    }

    private Invoice newInvoice(InvoiceKind kind, Customer customer, Appointment appointment,
                               com.vn.vitalcare.entity.Clinic clinic) {
        Invoice invoice = new Invoice();
        invoice.setKind(kind);
        invoice.setCustomer(customer);
        invoice.setAppointment(appointment);
        invoice.setClinic(clinic);
        invoice.setIssuedAt(OffsetDateTime.now(clock));
        return invoice;
    }

    /** Saved under a provisional number the unique column accepts, then its real one once the id exists. */
    private Invoice number(Invoice invoice) {
        invoice.setInvoiceNumber("HD-TMP-" + UUID.randomUUID());
        Invoice saved = invoices.persist(invoice);
        invoices.flush();
        saved.setInvoiceNumber("HD-%s-%05d".formatted(
                saved.getIssuedAt().atZoneSameInstant(zone()).format(DAY), saved.getId()));
        return invoices.persist(saved);
    }

    /**
     * What a bill being paid sets off: an examination bill lets its visit
     * queue (BF-02), ordered by when it was paid; a service's lets the service
     * be done, and the boards show it.
     */
    private void settled(Invoice invoice, OffsetDateTime when) {
        if (invoice.getKind() == InvoiceKind.EXAM && invoice.getAppointment() != null) {
            appointmentService.markExamPaid(invoice.getAppointment().getId(), when);
        } else if (invoice.getKind() == InvoiceKind.SERVICE) {
            events.publishEvent(new ExamRoomEvents.BoardChanged(invoice.getClinicId()));
        }
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
        settled(invoice, now);
        events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("invoices", invoice.getId(), "pay",
                Map.of("status", "UNPAID"), Map.of("status", "PAID", "method", method.name(),
                        "amount", invoice.getTotalAmount().toPlainString())));
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

    /** The examination bill of one of the caller's own visits. */
    @Transactional
    public InvoiceResponse ownForAppointment(Long appointmentId) {
        Invoice invoice = ownExamInvoice(appointmentId);
        return InvoiceResponse.from(invoice, Map.of());
    }

    /** Every bill of one of the caller's own visits: the examination's, then each ordered service's. */
    @Transactional
    public List<InvoiceResponse> ownAllForAppointment(Long appointmentId) {
        Appointment appointment = ownAppointment(appointmentId);
        if (owesExamFee(appointment)) {
            issueExam(appointment);
        }
        return invoices.findAllOf(appointment.getId()).stream()
                .map(invoice -> InvoiceResponse.from(invoice, Map.of()))
                .toList();
    }

    /**
     * The examination bill of a visit, for the desk taking the fee at check-in;
     * issued now for a visit registered before bills came first.
     */
    @Transactional
    public InvoiceResponse examFor(Long appointmentId) {
        Appointment appointment = appointmentService.get(appointmentId);
        Invoice invoice = owesExamFee(appointment) ? issueExam(appointment)
                : invoices.findExamOf(appointment.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Invoice for appointment", appointmentId));
        return InvoiceResponse.from(invoice, rowLevel.capabilities(invoice));
    }

    /**
     * Whether a visit should carry an examination bill it may not have yet:
     * one still registered or at the desk unpaid (registered before bills came
     * first). Never a referral — the fee was paid on the visit that sent the
     * patient — nor a visit already paid, over, cancelled or missed.
     */
    private static boolean owesExamFee(Appointment appointment) {
        var status = appointment.getStatus();
        return appointment.getReferredFrom() == null && appointment.getPaidAt() == null
                && (status == com.vn.vitalcare.entity.AppointmentStatus.BOOKED
                    || status == com.vn.vitalcare.entity.AppointmentStatus.CHECKED_IN);
    }

    /**
     * Every bill of the caller's account, the relatives' included, each with
     * its payments: the portal's payment history.
     * ponytail: unpaged, an account holds tens of bills; page it if that grows.
     */
    public List<InvoiceResponse> ownAll() {
        Customer customer = customerService.getForCurrentUser();
        return invoices.findOwn(customer.getId()).stream()
                .map(invoice -> InvoiceResponse.from(invoice, Map.of()))
                .toList();
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
        return startVnpayFor(ownExamInvoice(appointmentId), clientIp);
    }

    /** As {@link #startVnpay}, for any one of the caller's own bills: a service's too. */
    @Transactional
    public String startVnpayForInvoice(Long invoiceId, String clientIp) {
        return startVnpayFor(ownInvoiceById(invoiceId), clientIp);
    }

    private String startVnpayFor(Invoice owned, String clientIp) {
        if (!vnpay.enabled()) {
            throw new ConflictException("Online payment is not set up for this clinic yet");
        }
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
            settled(locked, now);
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

    /**
     * A transfer SePay saw arrive on the clinic's account. The bill is the one
     * its {@code VC…} code names; it is settled when the money covers it. Not
     * scoped: the webhook's signature is the authority, as VNPay's is.
     *
     * <p>Every transfer that names a bill is kept as a payment, under
     * {@code SEPAY-<SePay id>} (unique, so a re-sent webhook changes nothing):
     * short of the total it is recorded as failed for the desk to sort out,
     * and on a bill already paid it is recorded and flagged for a refund.
     */
    @Transactional
    public void settleTransfer(com.vn.vitalcare.care.billing.sepay.SepayTransfer transfer) {
        if (transfer.id() == null || transfer.transferAmount() == null
                || !"in".equalsIgnoreCase(transfer.transferType())) {
            return;
        }
        var invoiceId = com.vn.vitalcare.care.billing.sepay.BankTransfer.invoiceIdIn(transfer.code(), transfer.content());
        if (invoiceId.isEmpty()) {
            log.info("SePay transfer {} names no bill; left to the desk", transfer.id());
            log.debug("SePay transfer {} content: {}", transfer.id(), transfer.content());
            return;
        }
        String reference = "SEPAY-" + transfer.id();
        if (payments.findByTransactionCodeForUpdate(reference).isPresent()) {
            return;
        }
        Invoice invoice;
        try {
            invoice = lock(invoiceId.get());
        } catch (ResourceNotFoundException e) {
            log.warn("SePay transfer {} names bill {} which does not exist", transfer.id(), invoiceId.get());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Payment payment = new Payment(invoice, transfer.transferAmount(), PaymentMethod.BANK_TRANSFER, reference);
        if (Vnd.of(transfer.transferAmount()).compareTo(Vnd.of(invoice.getTotalAmount())) < 0) {
            payment.fail("SHORT", transfer.referenceCode());
            invoice.getPayments().add(payments.save(payment));
            log.warn("SePay transfer {} of {} is short of bill {} ({}); not settled",
                    transfer.id(), transfer.transferAmount(), invoice.getInvoiceNumber(), invoice.getTotalAmount());
            return;
        }
        payment.succeed(now, null, transfer.referenceCode());
        invoice.getPayments().add(payments.save(payment));
        if (Vnd.of(transfer.transferAmount()).compareTo(Vnd.of(invoice.getTotalAmount())) > 0) {
            log.warn("SePay transfer {} of {} is more than bill {} ({}): refund the difference",
                    transfer.id(), transfer.transferAmount(), invoice.getInvoiceNumber(), invoice.getTotalAmount());
        }
        if (invoice.getStatus() == InvoiceStatus.UNPAID) {
            invoice.markPaid(now);
            invoices.persist(invoice);
            settled(invoice, now);
        } else {
            log.warn("SePay transfer {} paid bill {} that was already {}: refund needed",
                    transfer.id(), invoice.getInvoiceNumber(), invoice.getStatus());
        }
    }

    /** What a gateway answer came to, and the appointment to send the customer back to. */
    public record Settlement(Outcome outcome, Long appointmentId) {

        public enum Outcome { PAID, FAILED, ALREADY_PAID, ALREADY_FAILED, NOT_FOUND, BAD_AMOUNT, BAD_SIGNATURE }

        static Settlement of(Outcome outcome, Long appointmentId) {
            return new Settlement(outcome, appointmentId);
        }
    }

    // --- helpers ------------------------------------------------------------------

    private Appointment ownAppointment(Long appointmentId) {
        return appointmentService.getOwn(customerService.getForCurrentUser(), appointmentId);
    }

    private Invoice ownExamInvoice(Long appointmentId) {
        Appointment appointment = ownAppointment(appointmentId);
        if (owesExamFee(appointment)) {
            return issueExam(appointment);
        }
        return invoices.findExamOf(appointment.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice for appointment", appointmentId));
    }

    /** One of the caller's bills (their own or a relative's), by id; anybody else's is not found. */
    private Invoice ownInvoiceById(Long invoiceId) {
        Customer customer = customerService.getForCurrentUser();
        return invoices.findOwn(customer.getId()).stream()
                .filter(invoice -> invoice.getId().equals(invoiceId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId));
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
