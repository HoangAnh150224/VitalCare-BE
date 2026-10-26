package com.vn.vitalcare.care.billing.vnpay;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VNPay's payment gateway, version 2.1.0: builds the signed URL a customer is
 * sent to, and checks the signature on what VNPay sends back.
 *
 * <p>The signature is HMAC-SHA512, keyed with the merchant's hash secret,
 * over the {@code vnp_} parameters sorted by name and URL-encoded the way
 * VNPay's own samples do (US-ASCII, space as {@code +}). The terminal code and
 * secret come from configuration ({@code APP_VNPAY_TMN_CODE},
 * {@code APP_VNPAY_HASH_SECRET}) and never from the repository; without them
 * the gateway is simply off.
 */
@Component
public class VnpayClient {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final String tmnCode;
    private final String hashSecret;
    private final String payUrl;
    private final String returnUrl;
    private final Clock clock;

    public VnpayClient(@Value("${app.vnpay.tmn-code:}") String tmnCode,
                       @Value("${app.vnpay.hash-secret:}") String hashSecret,
                       @Value("${app.vnpay.pay-url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}") String payUrl,
                       @Value("${app.vnpay.return-url:http://localhost:8080/api/payments/vnpay/return}") String returnUrl,
                       Clock clock) {
        this.tmnCode = tmnCode;
        this.hashSecret = hashSecret;
        this.payUrl = payUrl;
        this.returnUrl = returnUrl;
        this.clock = clock;
    }

    /** Whether a terminal is configured. Off, the API refuses online payment and the screen hides the button. */
    public boolean enabled() {
        return !tmnCode.isBlank() && !hashSecret.isBlank();
    }

    /**
     * The URL to send the customer to.
     *
     * @param txnRef    our reference for this attempt, unique, matched when VNPay answers
     * @param amount    in đồng; VNPay wants it times 100
     * @param orderInfo plain ASCII: VNPay rejects accented text here
     */
    public String paymentUrl(String txnRef, BigDecimal amount, String orderInfo, String clientIp) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(VIETNAM));
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString());
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", orderInfo);
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", clientIp == null || clientIp.isBlank() ? "127.0.0.1" : clientIp);
        params.put("vnp_CreateDate", now.format(STAMP));
        params.put("vnp_ExpireDate", now.plusMinutes(15).format(STAMP));

        String query = encode(params);
        return payUrl + "?" + query + "&vnp_SecureHash=" + sign(query);
    }

    /** Whether what VNPay sent back carries a valid signature over its own {@code vnp_} parameters. */
    public boolean verify(Map<String, String> received) {
        String given = received.get("vnp_SecureHash");
        if (!enabled() || given == null || given.isBlank()) {
            return false;
        }
        Map<String, String> signed = new TreeMap<>();
        received.forEach((name, value) -> {
            if (name.startsWith("vnp_") && !name.equals("vnp_SecureHash") && !name.equals("vnp_SecureHashType")) {
                signed.put(name, value);
            }
        });
        byte[] expected = sign(encode(signed)).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /** The sorted, encoded {@code name=value&...} string that is both the query and what is signed. */
    static String encode(Map<String, String> sorted) {
        return sorted.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.US_ASCII) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
    }

    String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA512 is not available", e);
        }
    }
}
