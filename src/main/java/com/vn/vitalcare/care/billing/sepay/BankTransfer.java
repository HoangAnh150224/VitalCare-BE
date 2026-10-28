package com.vn.vitalcare.care.billing.sepay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Paying a bill by bank transfer to the clinic's account: the account a
 * VietQR code points at, the code that ties a transfer to its bill, and the
 * signature SePay's webhook must carry when it reports money coming in.
 *
 * <p>All from the environment ({@code APP_TRANSFER_*}, {@code APP_SEPAY_SECRET});
 * with the account unset there is no transfer to offer, and with the secret
 * unset every webhook is refused.
 */
@Component
public class BankTransfer {

    /** The prefix SePay is set to recognise ("Cấu trúc mã thanh toán": VC, 6–8 digits). */
    public static final String PREFIX = "VC";
    /** VC and 6–8 digits standing on their own: not inside a longer word or number. */
    private static final Pattern CODE =
            Pattern.compile("(?<![A-Za-z0-9])" + PREFIX + "(\\d{6,8})(?!\\d)", Pattern.CASE_INSENSITIVE);
    /** How far a signed timestamp may be from now: SePay's own advice, against replays. */
    private static final Duration SKEW = Duration.ofMinutes(5);

    private final String bank;
    private final String accountNumber;
    private final String accountName;
    private final byte[] secret;

    public BankTransfer(@Value("${app.transfer.bank:}") String bank,
                        @Value("${app.transfer.account-number:}") String accountNumber,
                        @Value("${app.transfer.account-name:}") String accountName,
                        @Value("${app.sepay.secret:}") String secret) {
        this.bank = bank.trim();
        this.accountNumber = accountNumber.trim();
        this.accountName = accountName.trim().toUpperCase(Locale.ROOT);
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Whether there is an account to send the money to. */
    public boolean enabled() {
        return !bank.isEmpty() && !accountNumber.isEmpty();
    }

    /** What the patient writes in the transfer: {@code VC000017} for bill 17. */
    public static String codeFor(Long invoiceId) {
        return PREFIX + "%06d".formatted(invoiceId);
    }

    /** The bill a transfer names, read from SePay's recognised code or, failing that, the free text. */
    public static Optional<Long> invoiceIdIn(String... texts) {
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = CODE.matcher(text);
            if (matcher.find()) {
                return Optional.of(Long.valueOf(matcher.group(1)));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a webhook is SePay's: {@code X-SePay-Signature: sha256=<hex>} is
     * the HMAC-SHA256, under the shared secret, of {@code <timestamp>.<raw body>},
     * and {@code X-SePay-Timestamp} (Unix seconds) is within five minutes of now.
     * Compared in constant time; with no secret configured nothing passes.
     */
    public boolean signedBySepay(String timestamp, String signature, String rawBody, Instant now) {
        if (secret.length == 0 || timestamp == null || signature == null || rawBody == null
                || !signature.regionMatches(true, 0, "sha256=", 0, 7)) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(SKEW) > 0) {
            return false;
        }
        byte[] expected = hmac(timestamp.trim() + "." + rawBody);
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signature.substring(7).trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, given);
    }

    /** HMAC-SHA256 under the shared secret; public so a test can sign as SePay would. */
    public byte[] hmac(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Where to send the money for one bill, for the VietQR code and the details beside it. */
    public record Instructions(String bank, String accountNumber, String accountName, long amount, String content) {
    }

    public Instructions instructionsFor(Long invoiceId, long amount) {
        return new Instructions(bank, accountNumber, accountName, amount, codeFor(invoiceId));
    }
}
