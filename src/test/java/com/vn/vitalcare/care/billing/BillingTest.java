package com.vn.vitalcare.care.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.ClinicRowLevel;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.care.billing.catalog.MedicalServiceCatalog;
import com.vn.vitalcare.care.billing.sepay.SepayTransfer;
import com.vn.vitalcare.entity.PaymentStatus;
import com.vn.vitalcare.care.billing.invoice.InvoiceRepository;
import com.vn.vitalcare.care.billing.invoice.InvoiceResponse;
import com.vn.vitalcare.care.billing.invoice.Invoices;
import com.vn.vitalcare.care.billing.invoice.Invoices.Settlement.Outcome;
import com.vn.vitalcare.care.billing.invoice.PaymentRepository;
import com.vn.vitalcare.care.billing.vnpay.VnpayClient;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.serviceorder.ServiceOrderRepository;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.Invoice;
import com.vn.vitalcare.entity.InvoiceStatus;
import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.Payment;
import com.vn.vitalcare.entity.PaymentMethod;
import com.vn.vitalcare.entity.ServiceCategory;
import com.vn.vitalcare.entity.ServiceOrder;
import com.vn.vitalcare.share.exception.ConflictException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * What a bill guarantees (Report 1 FE-23): the visit and its services at the
 * prices they were ordered at, settled once, and a VNPay answer believed only
 * when signed, for the right amount, the first time.
 */
class BillingTest {

    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
    private static final Instant NOW = Instant.parse("2026-10-26T03:00:00Z");
    private static final String SECRET = "TESTSECRETTESTSECRETTESTSECRET12";

    private InvoiceRepository invoices;
    private PaymentRepository payments;
    private ServiceOrderRepository orders;
    private VnpayClient vnpay;
    private CustomerService customers;
    private Invoices service;
    private final Map<String, Payment> byRef = new HashMap<>();

    private Clinic clinic;
    private MedicalService consultation;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        invoices = mock(InvoiceRepository.class);
        payments = mock(PaymentRepository.class);
        orders = mock(ServiceOrderRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        consultation = service("KHAM", ServiceCategory.CONSULTATION, 150_000);
        MedicalServiceCatalog catalog = mock(MedicalServiceCatalog.class);
        when(catalog.consultation()).thenReturn(consultation);
        ClinicService clinicService = mock(ClinicService.class);
        when(clinicService.timeZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));

        when(invoices.findByEncounter(any())).thenReturn(Optional.empty());
        when(invoices.persist(any())).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            if (invoice.getId() == null) {
                ReflectionTestUtils.setField(invoice, "id", 42L);
            }
            return invoice;
        });
        when(payments.save(any())).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            byRef.put(payment.getTransactionCode(), payment);
            return payment;
        });
        when(payments.findByTransactionCodeForUpdate(any()))
                .thenAnswer(invocation -> Optional.ofNullable(byRef.get(invocation.<String>getArgument(0))));

        vnpay = new VnpayClient("TESTTMN1", SECRET, "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
                "http://localhost:8080/api/payments/vnpay/return", clock);
        customers = mock(CustomerService.class);
        service = new Invoices(invoices, payments, orders, catalog, mock(AppointmentService.class),
                customers, clinicService, () -> Optional.of(CLINIC), vnpay,
                new ClinicRowLevel(() -> CLINIC), mock(EntityManager.class), clock);

        clinic = new Clinic();
        clinic.setClinicId(CLINIC);
    }

    @Test
    @DisplayName("concluding a visit bills the examination and every service not cancelled, at the ordered price")
    void issueBillsTheVisitAndItsServices() {
        ClinicalEncounter encounter = encounter();
        ServiceOrder blood = order(encounter, service("XN-CTM", ServiceCategory.LAB, 120_000), 100_000);
        ServiceOrder xray = order(encounter, service("XQ", ServiceCategory.IMAGING, 180_000), 180_000);
        xray.cancel(null, "không cần nữa");
        when(orders.findByEncounter(1L)).thenReturn(List.of(blood, xray));

        Invoice invoice = service.issueFor(encounter);

        assertEquals(2, invoice.getItems().size(), "the cancelled X-ray is not billed");
        assertEquals(0, new BigDecimal("250000").compareTo(invoice.getTotalAmount()),
                "150 000 examination + 100 000 as ordered, not the 120 000 the catalogue says now");
        assertEquals(InvoiceStatus.UNPAID, invoice.getStatus());
        assertEquals("HD-20261026-00042", invoice.getInvoiceNumber());
    }

    @Test
    @DisplayName("a bill is settled at the counter once; the second clerk is told it is paid")
    @SuppressWarnings("unchecked")
    void counterPaymentOnce() {
        Invoice invoice = issued();
        when(invoices.findOne(any(Specification.class))).thenReturn(Optional.of(invoice));
        when(invoices.findForUpdate(42L)).thenReturn(Optional.of(invoice));

        assertEquals(InvoiceStatus.PAID, service.payAtCounter(42L, PaymentMethod.CASH).status());
        assertThrows(ConflictException.class, () -> service.payAtCounter(42L, PaymentMethod.CASH));
        assertEquals(1, byRef.size());
    }

    @Test
    @DisplayName("payment history: the caller's account's bills, each with how it was paid")
    @SuppressWarnings("unchecked")
    void ownHistory() {
        Customer me = new Customer();
        ReflectionTestUtils.setField(me, "id", 37L);
        when(customers.getForCurrentUser()).thenReturn(me);
        Invoice invoice = issued();
        when(invoices.findOne(any(Specification.class))).thenReturn(Optional.of(invoice));
        when(invoices.findForUpdate(42L)).thenReturn(Optional.of(invoice));
        service.payAtCounter(42L, PaymentMethod.CASH);
        when(invoices.findOwn(37L)).thenReturn(List.of(invoice));

        List<InvoiceResponse> history = service.ownAll();

        assertEquals(1, history.size());
        assertEquals(InvoiceStatus.PAID, history.get(0).status());
        assertEquals(1, history.get(0).payments().size(), "the cash payment is listed");
        verify(invoices).findOwn(37L);
    }

    private static SepayTransfer transferIn(long sepayId, long amount, String code, String content) {
        return new SepayTransfer(sepayId, "MBBank", "2026-10-26 10:00:00", "0123456789", code, content, "in",
                BigDecimal.valueOf(amount), "FT26300" + sepayId);
    }

    @Test
    @DisplayName("bank transfer: enough money with the bill's code settles it, once, however often SePay re-sends")
    void transferSettlesOnce() {
        Invoice invoice = issued();
        when(invoices.findForUpdate(42L)).thenReturn(Optional.of(invoice));

        service.settleTransfer(transferIn(901, 250_000, "VC000042", "VC000042 kham benh"));
        service.settleTransfer(transferIn(901, 250_000, "VC000042", "VC000042 kham benh"));

        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        assertEquals(1, byRef.size(), "the re-sent webhook adds nothing");
        Payment payment = byRef.get("SEPAY-901");
        assertEquals(PaymentMethod.BANK_TRANSFER, payment.getMethod());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals("FT26300901", payment.getProviderRef());
    }

    @Test
    @DisplayName("bank transfer: short of the total is kept as a failed payment and the bill stays unpaid")
    void shortTransfer() {
        Invoice invoice = issued();
        when(invoices.findForUpdate(42L)).thenReturn(Optional.of(invoice));

        service.settleTransfer(transferIn(902, 100_000, "VC000042", "VC000042"));

        assertEquals(InvoiceStatus.UNPAID, invoice.getStatus());
        assertEquals(PaymentStatus.FAILED, byRef.get("SEPAY-902").getStatus());
        assertEquals("SHORT", byRef.get("SEPAY-902").getResponseCode());
    }

    @Test
    @DisplayName("bank transfer: the code is found in the content when SePay did not recognise one")
    void codeInContent() {
        Invoice invoice = issued();
        when(invoices.findForUpdate(42L)).thenReturn(Optional.of(invoice));

        service.settleTransfer(transferIn(903, 250_000, null, "NGUYEN VAN A chuyen tien vc000042"));

        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
    }

    @Test
    @DisplayName("bank transfer: money out, no code, or a bill that does not exist changes nothing")
    void ignoredTransfers() {
        when(invoices.findForUpdate(any())).thenReturn(Optional.empty());

        service.settleTransfer(new SepayTransfer(904L, "MBBank", null, null, "VC000042", null, "out",
                BigDecimal.valueOf(250_000), null));
        service.settleTransfer(transferIn(905, 250_000, null, "an sang"));
        service.settleTransfer(transferIn(906, 250_000, "VC999999", null));

        assertTrue(byRef.isEmpty());
    }

    @Test
    @DisplayName("the counter does not take money while the patient is on VNPay's page")
    @SuppressWarnings("unchecked")
    void counterWaitsForAnOnlineAttempt() {
        Invoice invoice = issued();
        when(invoices.findOne(any(Specification.class))).thenReturn(Optional.of(invoice));
        when(invoices.findForUpdate(42L)).thenReturn(Optional.of(invoice));
        Payment online = new Payment(invoice, invoice.getTotalAmount(), PaymentMethod.VNPAY, "42-000009");
        ReflectionTestUtils.setField(online, "createdAt", NOW.minusSeconds(120));
        invoice.getPayments().add(online);

        assertThrows(ConflictException.class, () -> service.payAtCounter(42L, PaymentMethod.CASH));
        ReflectionTestUtils.setField(online, "createdAt", NOW.minusSeconds(16 * 60));
        assertEquals(InvoiceStatus.PAID, service.payAtCounter(42L, PaymentMethod.CASH).status(),
                "an attempt older than its URL's 15 minutes no longer holds the bill");
    }

    @Test
    @DisplayName("the payment URL is signed, and carries the amount times 100")
    void paymentUrlIsSigned() {
        String url = vnpay.paymentUrl("42-123456", new BigDecimal("250000"), "Thanh toan hoa don HD-1", "1.2.3.4");
        Map<String, String> params = query(url);

        assertEquals("25000000", params.get("vnp_Amount"));
        assertEquals("TESTTMN1", params.get("vnp_TmnCode"));
        assertTrue(vnpay.verify(params), "the gateway's own check of our signature passes");
        params.put("vnp_Amount", "100");
        assertFalse(vnpay.verify(params), "a changed parameter breaks it");
    }

    @Test
    @DisplayName("a signed success settles the bill once; a replay finds it settled")
    @SuppressWarnings("unchecked")
    void vnpaySuccessOnce() {
        Invoice invoice = pendingVnpay("42-000001");

        assertEquals(Outcome.PAID, service.settleVnpay(answer("42-000001", "25000000", "00")).outcome());
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        assertEquals(Outcome.ALREADY_PAID, service.settleVnpay(answer("42-000001", "25000000", "00")).outcome());
    }

    @Test
    @DisplayName("an unsigned, re-priced or failed answer does not settle the bill")
    void vnpayRefusals() {
        Invoice invoice = pendingVnpay("42-000002");

        Map<String, String> forged = answer("42-000002", "25000000", "00");
        forged.put("vnp_SecureHash", "00" + forged.get("vnp_SecureHash").substring(2));
        assertEquals(Outcome.BAD_SIGNATURE, service.settleVnpay(forged).outcome());
        assertEquals(Outcome.BAD_AMOUNT, service.settleVnpay(answer("42-000002", "100", "00")).outcome());
        assertEquals(Outcome.FAILED, service.settleVnpay(answer("42-000002", "25000000", "24")).outcome());
        assertEquals(InvoiceStatus.UNPAID, invoice.getStatus(), "a cancelled payment leaves the bill to pay again");
    }

    // --- helpers ------------------------------------------------------------------

    private Invoice pendingVnpay(String ref) {
        Invoice invoice = issued();
        when(invoices.findForUpdate(42L)).thenReturn(Optional.of(invoice));
        Payment payment = new Payment(invoice, invoice.getTotalAmount(), PaymentMethod.VNPAY, ref);
        byRef.put(ref, payment);
        return invoice;
    }

    /** What VNPay sends back, signed with the merchant secret the way VNPay signs it. */
    private static Map<String, String> answer(String ref, String amount, String code) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TmnCode", "TESTTMN1");
        params.put("vnp_TxnRef", ref);
        params.put("vnp_Amount", amount);
        params.put("vnp_ResponseCode", code);
        params.put("vnp_TransactionStatus", code);
        params.put("vnp_TransactionNo", "14000000");
        params.put("vnp_OrderInfo", "Thanh toan hoa don");
        String data = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
        params.put("vnp_SecureHash", hmac(data));
        return params;
    }

    private static String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(String url) {
        return java.util.Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv[1], StandardCharsets.US_ASCII),
                        (a, b) -> b, TreeMap::new));
    }

    private Invoice issued() {
        when(orders.findByEncounter(1L)).thenReturn(List.of(order(encounter(),
                service("XN-CTM", ServiceCategory.LAB, 100_000), 100_000)));
        return service.issueFor(encounter());
    }

    private ClinicalEncounter encounter() {
        ClinicalEncounter encounter = new ClinicalEncounter();
        ReflectionTestUtils.setField(encounter, "id", 1L);
        encounter.setClinic(clinic);
        encounter.setCustomer(new Customer());
        return encounter;
    }

    private static ServiceOrder order(ClinicalEncounter encounter, MedicalService service, long price) {
        ServiceOrder order = new ServiceOrder();
        order.setEncounter(encounter);
        order.setService(service);
        order.setUnitPrice(BigDecimal.valueOf(price));
        return order;
    }

    private static MedicalService service(String code, ServiceCategory category, long price) {
        MedicalService service = new MedicalService();
        service.setCode(code);
        service.setName(code);
        service.setCategory(category);
        service.setPrice(BigDecimal.valueOf(price));
        return service;
    }
}
