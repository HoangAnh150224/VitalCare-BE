package com.vn.vitalcare.care.billing.sepay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SePay's signature is checked as SePay signs, and a transfer's bill is read from its code. */
class BankTransferTest {

    private static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");
    private static final String BODY = "{\"id\":1,\"transferType\":\"in\",\"transferAmount\":150000,\"code\":\"VC000017\"}";

    private final BankTransfer transfer = new BankTransfer("MB", "0123456789", "Nguyen Van A", "whsec_test");

    private String sign(String timestamp, String body) {
        return "sha256=" + HexFormat.of().formatHex(transfer.hmac(timestamp + "." + body));
    }

    @Test
    @DisplayName("a body signed with the shared secret, now, is SePay's")
    void validSignature() {
        String ts = String.valueOf(NOW.getEpochSecond());
        assertTrue(transfer.signedBySepay(ts, sign(ts, BODY), BODY, NOW));
    }

    @Test
    @DisplayName("a changed body, a wrong secret, an old timestamp or no signature is refused")
    void forgeries() {
        String ts = String.valueOf(NOW.getEpochSecond());
        assertFalse(transfer.signedBySepay(ts, sign(ts, BODY), BODY.replace("150000", "1"), NOW), "body changed");
        BankTransfer other = new BankTransfer("MB", "0123456789", "A", "whsec_other");
        assertFalse(other.signedBySepay(ts, sign(ts, BODY), BODY, NOW), "another secret");
        String old = String.valueOf(NOW.minusSeconds(600).getEpochSecond());
        assertFalse(transfer.signedBySepay(old, sign(old, BODY), BODY, NOW), "replayed ten minutes later");
        assertFalse(transfer.signedBySepay(ts, null, BODY, NOW), "no signature");
        assertFalse(transfer.signedBySepay(ts, "sha256=zz", BODY, NOW), "not hex");
        BankTransfer unset = new BankTransfer("MB", "0123456789", "A", "");
        assertFalse(unset.signedBySepay(ts, sign(ts, BODY), BODY, NOW), "no secret configured: nothing passes");
    }

    @Test
    @DisplayName("the bill comes from SePay's code, else from the content, in any case")
    void codes() {
        assertEquals("VC000017", BankTransfer.codeFor(17L));
        assertEquals(Optional.of(17L), BankTransfer.invoiceIdIn("VC000017", null));
        assertEquals(Optional.of(17L), BankTransfer.invoiceIdIn("", "NGUYEN VAN A chuyen tien vc000017 kham benh"));
        assertEquals(Optional.empty(), BankTransfer.invoiceIdIn(null, "chuyen tien an sang"));
        assertEquals(Optional.empty(), BankTransfer.invoiceIdIn("VC123"), "too short to be a bill code");
        assertEquals(Optional.empty(), BankTransfer.invoiceIdIn("VC000000017"), "too long: another number, not a bill");
        assertEquals(Optional.empty(), BankTransfer.invoiceIdIn("ABCVC000017"), "inside a longer word");
    }
}
