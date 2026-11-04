package com.vn.vitalcare.care.billing.vnpay;

import com.vn.vitalcare.care.billing.invoice.Invoices;
import com.vn.vitalcare.care.billing.invoice.Invoices.Settlement;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where VNPay answers: {@code /api/payments/vnpay/return} (the customer's
 * browser, sent back after paying) and {@code /api/payments/vnpay/ipn}
 * (VNPay's server, which only reaches a public address). Both are open in
 * {@code SecurityConfig}: neither carries a token. What makes them
 * trustworthy is the signature, checked before anything is read.
 */
@RestController
@RequestMapping("/api/payments/vnpay")
public class VnpayController {

    private final Invoices invoices;
    private final String frontendUrl;

    public VnpayController(Invoices invoices, @Value("${app.vnpay.fe-base-url:http://localhost:5173}") String frontendUrl) {
        this.invoices = invoices;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /** Settles (if the IPN has not yet) and sends the customer back to their appointment with the outcome. */
    @GetMapping("/return")
    public ResponseEntity<Void> returned(@RequestParam Map<String, String> params) {
        Settlement settlement = invoices.settleVnpay(params);
        String outcome = switch (settlement.outcome()) {
            case PAID, ALREADY_PAID -> "success";
            case BAD_SIGNATURE, BAD_AMOUNT, NOT_FOUND -> "invalid";
            default -> "failed";
        };
        String target = settlement.appointmentId() == null
                ? frontendUrl + "/my-appointments?payment=" + outcome
                : frontendUrl + "/my-appointments/show/" + settlement.appointmentId() + "?payment=" + outcome;
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    /** VNPay's server-to-server notification, answered in the shape its spec asks for. */
    @GetMapping("/ipn")
    public Map<String, String> ipn(@RequestParam Map<String, String> params) {
        Settlement settlement = invoices.settleVnpay(params);
        return switch (settlement.outcome()) {
            case PAID, FAILED -> answer("00", "Confirm Success");
            case ALREADY_PAID, ALREADY_FAILED -> answer("02", "Order already confirmed");
            case NOT_FOUND -> answer("01", "Order not found");
            case BAD_AMOUNT -> answer("04", "Invalid amount");
            case BAD_SIGNATURE -> answer("97", "Invalid signature");
        };
    }

    private static Map<String, String> answer(String code, String message) {
        return Map.of("RspCode", code, "Message", message);
    }
}
