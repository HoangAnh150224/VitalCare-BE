package com.vn.vitalcare.care.billing.sepay;

import com.vn.vitalcare.care.billing.invoice.Invoices;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * SePay's webhook: money moved on the clinic's account. Open to the internet
 * (SecurityConfig), so the HMAC-SHA256 signature over the raw body is the only
 * proof it is SePay; without a valid one, 401 and nothing read. The body is
 * taken raw because the signature covers the bytes as sent.
 *
 * <p>Anything it could make sense of is answered {@code {"success": true}},
 * including transfers it ignored, so SePay does not keep re-sending them.
 */
@RestController
@RequestMapping("/api/payments/sepay")
public class SepayController {

    private static final Logger log = LoggerFactory.getLogger(SepayController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BankTransfer transfer;
    private final Invoices invoices;
    private final Clock clock;

    public SepayController(BankTransfer transfer, Invoices invoices, Clock clock) {
        this.transfer = transfer;
        this.invoices = invoices;
        this.clock = clock;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> received(
            @RequestHeader(value = "X-SePay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-SePay-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        if (!transfer.signedBySepay(timestamp, signature, rawBody, clock.instant())) {
            log.warn("SePay webhook refused: missing or invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false));
        }
        SepayTransfer body;
        try {
            body = JSON.readValue(rawBody, SepayTransfer.class);
        } catch (JacksonException e) {
            log.warn("SePay webhook with a body that is not a transaction: {}", e.getOriginalMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false));
        }
        invoices.settleTransfer(body);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
