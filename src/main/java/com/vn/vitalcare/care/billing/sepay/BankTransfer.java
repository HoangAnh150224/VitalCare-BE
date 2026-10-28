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
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Paying a bill by bank transfer to the clinic's account: the account a
 * VietQR code points at, the code that ties a transfer to its bill, and the
 * signature SePay's webhook must carry when it reports money coming in.
 *
 * <p>The account is set by {@code APP_TRANSFER_*}, or, with those empty and
 * {@code APP_SEPAY_API_TOKEN} set, read from SePay itself: its bank account
 * list, the first active one, or the one {@code APP_TRANSFER_ACCOUNT_NUMBER}
 * names. With no account there is no transfer to offer; with no
 * {@code APP_SEPAY_SECRET} every webhook is refused.
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

    /** After SePay could not be asked, how long before asking again. */
    private static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(BankTransfer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The account money is sent to: bank as VietQR knows it (short name or BIN),
     * the bank's name to show, number, holder.
     */
    public record Account(String bank, String bankName, String number, String holder) {
    }

    private final Account configured;
    private final String wantedNumber;
    private final String apiToken;
    private final RestClient sepay;
    private final byte[] secret;
    /** What SePay said, once it said it; asked again only after a failure. */
    private final AtomicReference<Account> fromSepay = new AtomicReference<>();
    private volatile Instant nextAttempt = Instant.MIN;

    public BankTransfer(@Value("${app.transfer.bank:}") String bank,
                        @Value("${app.transfer.account-number:}") String accountNumber,
                        @Value("${app.transfer.account-name:}") String accountName,
                        @Value("${app.sepay.secret:}") String secret,
                        @Value("${app.sepay.api-token:}") String apiToken,
                        @Value("${app.sepay.api-url:https://my.sepay.vn}") String apiUrl) {
        String number = accountNumber.trim();
        this.configured = bank.isBlank() || number.isEmpty()
                ? null
                : new Account(bank.trim(), bank.trim(), number, accountName.trim().toUpperCase(Locale.ROOT));
        this.wantedNumber = number;
        this.apiToken = apiToken.trim();
        this.secret = secret.trim().getBytes(StandardCharsets.UTF_8);
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        this.sepay = RestClient.builder().baseUrl(apiUrl.replaceAll("/+$", "")).requestFactory(timeouts).build();
    }

    /** Whether there is an account to send the money to. */
    public boolean enabled() {
        return account().isPresent();
    }

    /**
     * The account to pay into: the configured one, else SePay's, asked once
     * and remembered; after a failure asked again a minute later at most.
     */
    public Optional<Account> account() {
        if (configured != null) {
            return Optional.of(configured);
        }
        Account known = fromSepay.get();
        if (known != null || apiToken.isEmpty() || Instant.now().isBefore(nextAttempt)) {
            return Optional.ofNullable(known);
        }
        Optional<Account> asked = askSepay();
        asked.ifPresentOrElse(fromSepay::set, () -> nextAttempt = Instant.now().plus(RETRY_AFTER));
        return asked;
    }

    /** {@code GET /userapi/bankaccounts/list}: the first active account, or the one asked for by number. */
    private Optional<Account> askSepay() {
        String body;
        try {
            body = sepay.get()
                    .uri("/userapi/bankaccounts/list")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // Never the token: only what went wrong.
            log.warn("Could not read the bank accounts from SePay: {}", e.getMessage());
            return Optional.empty();
        }
        try {
            JsonNode accounts = JSON.readTree(body == null ? "{}" : body).path("bankaccounts");
            for (JsonNode candidate : accounts) {
                String number = candidate.path("account_number").asString("").trim();
                boolean active = "1".equals(candidate.path("active").asString(""));
                if (!active || number.isEmpty() || (!wantedNumber.isEmpty() && !wantedNumber.equals(number))) {
                    continue;
                }
                String bank = candidate.path("bank_bin").asString("").trim();
                if (bank.isEmpty()) {
                    bank = candidate.path("bank_short_name").asString("").trim();
                }
                String holder = candidate.path("account_holder_name").asString("").trim().toUpperCase(Locale.ROOT);
                String name = candidate.path("bank_short_name").asString(bank).trim();
                log.info("Bank transfers go to the {} account ending {} (from SePay)",
                        name, number.substring(Math.max(0, number.length() - 3)));
                return Optional.of(new Account(bank, name.isEmpty() ? bank : name, number, holder));
            }
            log.warn("SePay lists no active bank account{}", wantedNumber.isEmpty() ? "" : " with the configured number");
        } catch (JacksonException e) {
            log.warn("SePay's bank account list was not JSON: {}", e.getOriginalMessage());
        }
        return Optional.empty();
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
        return rejection(timestamp, signature, rawBody, now) == null;
    }

    /**
     * Why a webhook is not SePay's, for the log, or null when it is. Says which
     * check failed, never the secret or the expected signature.
     */
    public String rejection(String timestamp, String signature, String rawBody, Instant now) {
        if (secret.length == 0) {
            return "no secret configured (APP_SEPAY_SECRET is empty)";
        }
        if (timestamp == null || signature == null) {
            return "missing X-SePay-Timestamp or X-SePay-Signature header";
        }
        if (rawBody == null) {
            return "empty body";
        }
        if (!signature.regionMatches(true, 0, "sha256=", 0, 7)) {
            return "signature is not in the form sha256=<hex>";
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return "timestamp is not Unix seconds";
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(seconds), now).abs();
        if (skew.compareTo(SKEW) > 0) {
            return "timestamp is " + skew.toSeconds() + "s from the server clock (more than 5 minutes)";
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signature.substring(7).trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "signature is not hex";
        }
        if (!MessageDigest.isEqual(hmac(timestamp.trim() + "." + rawBody), given)) {
            return "signature does not match: the secret differs from SePay's, or the body was changed";
        }
        return null;
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
    public record Instructions(String bank, String bankName, String accountNumber, String accountName,
                               long amount, String content) {
    }

    /** Call only when {@link #enabled()}. */
    public Instructions instructionsFor(Long invoiceId, long amount) {
        Account account = account().orElseThrow(() -> new IllegalStateException("No account to transfer to"));
        return new Instructions(account.bank(), account.bankName(), account.number(), account.holder(), amount,
                codeFor(invoiceId));
    }
}
