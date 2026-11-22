package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * FE-12 Invoices and FE-23 Online Payment / Clinic Payment Confirmation / Revenue Report end to end: the exam-fee
 * bill of a booking, payment at the counter (InvoiceController), the bank's webhook (SepayController, HMAC-SHA256
 * signed) and VNPay's IPN (VnpayController, HMAC-SHA512 signed) settling a bill exactly once, and the revenue report.
 * The payment gateways are the outside systems here: the test signs their calls with test secrets as they would.
 */
@TestPropertySource(properties = {
        "app.sepay.secret=it-sepay-secret",
        "app.vnpay.tmn-code=ITTEST01",
        "app.vnpay.hash-secret=ITHASHSECRET0123456789"})
class BillingIT extends CommittedIntegrationTest {

    private String desk;

    @BeforeEach
    void staff() throws Exception {
        freshDoctor();
        desk = tokenOf(DESK_1);
    }

    private record Bill(String phone, String token, Long visit, Long invoice) {
    }

    /** A patient booked today: the exam-fee bill exists, unpaid. */
    private Bill bill() throws Exception {
        String phone = newPhone();
        patient(phone);
        String own = tokenFor(phone, "Passw0rd!");
        Long visit = Long.valueOf(json(callJson(own, post("/api/my_appointments"), """
                {"clinicId":"%s","appointmentDate":"%s","reason":"Khám tim","department":"Tim mạch"}""".formatted(CLINIC_1, TODAY))
                .andExpect(status().isCreated()), "$.id"));
        Long invoice = Long.valueOf(json(call(desk, get("/api/invoices/exam").param("appointmentId", visit.toString()))
                .andExpect(status().isOk()), "$.id"));
        return new Bill(phone, own, visit, invoice);
    }

    private int payments(Long invoice) {
        return count("select count(*) from payment where invoice_id = ? and status = 'PAID'", invoice);
    }

    @Test
    void bil01_bookingCreatesTheExamFeeBillSeenByDeskAndPatient() throws Exception {
        Bill b = bill();
        call(desk, get("/api/invoices/" + b.invoice())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unpaid"))
                .andExpect(jsonPath("$.totalAmount").value(150000.0));
        call(b.token(), get("/api/my_appointments/" + b.visit() + "/invoice")).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.id").value(b.invoice()));
        call(b.token(), get("/api/my_invoices")).andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(b.invoice().intValue())));
    }

    /** Clinic Payment Confirmation: paid once at the counter; a second cashier is told it is already paid. */
    @Test
    void bil02_counterPaymentOnce() throws Exception {
        Bill b = bill();
        callJson(desk, post("/api/invoices/" + b.invoice() + "/payments"), "{\"method\":\"CASH\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"));
        callJson(desk, post("/api/invoices/" + b.invoice() + "/payments"), "{\"method\":\"CASH\"}").andExpect(status().isConflict());
        assertEquals(1, payments(b.invoice()));
        callJson(desk, post("/api/invoices/" + b.invoice() + "/payments"), "{}").andExpect(status().isUnprocessableContent());
    }

    // ---------------------------------------------------------------- SePay (bank transfer)
    private static String hmac(String algorithm, String secret, String text) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private org.springframework.test.web.servlet.ResultActions sepay(String body, String secret) throws Exception {
        String ts = String.valueOf(java.time.Instant.now().getEpochSecond());
        return mvc.perform(post("/api/payments/sepay/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-SePay-Timestamp", ts).header("X-SePay-Signature", "sha256=" + hmac("HmacSHA256", secret, ts + "." + body))
                .content(body));
    }

    private static String transfer(long id, Long invoice, int amount) {
        return """
                {"id":%d,"gateway":"MBBank","transactionDate":"2026-11-22 10:00:00","accountNumber":"0123456789",
                 "code":null,"content":"THANH TOAN VC%06d","transferType":"in","transferAmount":%d,"referenceCode":"FT%d"}"""
                .formatted(id, invoice, amount, id);
    }

    /** The bank's webhook, signed by SePay, settles the bill once even when SePay sends it again. */
    @Test
    void bil03_signedTransferSettlesTheBillOnce() throws Exception {
        Bill b = bill();
        long id = System.nanoTime() % 1_000_000_000L;
        sepay(transfer(id, b.invoice(), 150000), "it-sepay-secret").andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        sepay(transfer(id, b.invoice(), 150000), "it-sepay-secret").andExpect(status().isOk());
        assertEquals("PAID", jdbc.queryForObject("select status from invoice where id = ?", String.class, b.invoice()));
        assertEquals(1, payments(b.invoice()));
        call(b.token(), get("/api/my_appointments/" + b.visit())).andExpect(jsonPath("$.paidAt").isNotEmpty());
    }

    @Test
    void bil04_transferShortOfTheTotalLeavesTheBillUnpaid() throws Exception {
        Bill b = bill();
        sepay(transfer(System.nanoTime() % 1_000_000_000L, b.invoice(), 100000), "it-sepay-secret").andExpect(status().isOk());
        assertEquals("UNPAID", jdbc.queryForObject("select status from invoice where id = ?", String.class, b.invoice()));
        assertEquals(0, payments(b.invoice()));
    }

    /** Anyone can reach the webhook; without SePay's signature it is refused and nothing changes. */
    @Test
    void bil05_unsignedWebhookIsRefused() throws Exception {
        Bill b = bill();
        sepay(transfer(System.nanoTime() % 1_000_000_000L, b.invoice(), 150000), "not-the-secret")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        assertEquals("UNPAID", jdbc.queryForObject("select status from invoice where id = ?", String.class, b.invoice()));
    }

    // ---------------------------------------------------------------- VNPay
    private static Map<String, String> query(String url) {
        return java.util.Arrays.stream(url.substring(url.indexOf('?') + 1).split("&")).map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
    }

    /** VNPay's server-to-server call, signed the way VNPay signs (sorted vnp_ fields, URL-encoded, HMAC-SHA512). */
    private org.springframework.test.web.servlet.ResultActions ipn(Map<String, String> fields) throws Exception {
        Map<String, String> sorted = new TreeMap<>(fields);
        String signed = sorted.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII)).collect(Collectors.joining("&"));
        var request = get("/api/payments/vnpay/ipn");
        sorted.forEach(request::param);
        return mvc.perform(request.param("vnp_SecureHash", hmac("HmacSHA512", "ITHASHSECRET0123456789", signed)));
    }

    private Map<String, String> startVnpay(Bill b) throws Exception {
        String url = json(call(b.token(), post("/api/my_appointments/" + b.visit() + "/invoice/vnpay")).andExpect(status().isOk()), "$.url");
        assertTrue(url.contains("vnp_SecureHash="), url);
        Map<String, String> q = query(url);
        return new TreeMap<>(Map.of("vnp_TxnRef", q.get("vnp_TxnRef"), "vnp_Amount", q.get("vnp_Amount"), "vnp_TmnCode", "ITTEST01",
                "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00", "vnp_TransactionNo", "14" + System.nanoTime() % 1_000_000,
                "vnp_BankCode", "NCB", "vnp_PayDate", "20261122100000"));
    }

    @Test
    void bil06_vnpaySuccessSettlesOnce() throws Exception {
        Bill b = bill();
        Map<String, String> fields = startVnpay(b);
        assertEquals("15000000", fields.get("vnp_Amount"));
        ipn(fields).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));
        ipn(fields).andExpect(jsonPath("$.RspCode").value("02"));
        assertEquals("PAID", jdbc.queryForObject("select status from invoice where id = ?", String.class, b.invoice()));
        assertEquals(1, payments(b.invoice()));
    }

    @Test
    void bil07_vnpayWithAlteredAmountOrSignatureIsNotPaid() throws Exception {
        Bill b = bill();
        Map<String, String> fields = startVnpay(b);
        Map<String, String> cheaper = new TreeMap<>(fields);
        cheaper.put("vnp_Amount", "100");
        ipn(cheaper).andExpect(jsonPath("$.RspCode").value("04"));
        var forged = get("/api/payments/vnpay/ipn");
        fields.forEach(forged::param);
        mvc.perform(forged.param("vnp_SecureHash", "00ff")).andExpect(jsonPath("$.RspCode").value("97"));
        assertEquals("UNPAID", jdbc.queryForObject("select status from invoice where id = ?", String.class, b.invoice()));
    }

    /** Revenue Report: the clinic administrator's, up to a year at a time. */
    @Test
    void bil08_revenueReportCountsTheDaysTakings() throws Exception {
        Bill b = bill();
        callJson(desk, post("/api/invoices/" + b.invoice() + "/payments"), "{\"method\":\"CASH\"}").andExpect(status().isOk());
        String report = call(tokenOf(CLINIC_ADMIN_1), get("/api/invoices/report").param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(report.contains(TODAY.toString()), report);
        call(tokenOf(CLINIC_ADMIN_1), get("/api/invoices/report").param("from", TODAY.minusYears(2).toString()).param("to", TODAY.toString()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void bil09_onlyBillingStaffReachInvoices() throws Exception {
        Bill b = bill();
        call(b.token(), get("/api/invoices/" + b.invoice())).andExpect(status().isForbidden());
        call(desk, get("/api/invoices/report").param("from", TODAY.toString()).param("to", TODAY.toString())).andExpect(status().isForbidden());
        call(tokenOf(DESK_2), get("/api/invoices/" + b.invoice())).andExpect(status().isNotFound());
    }
}
