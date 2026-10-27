package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends one-time codes by SMS through eSMS.vn, with the account in
 * {@code app.sms.esms.*} (from the environment only). Active whenever
 * {@code ESMS_API_KEY} is set; without it {@link LoggingOtpSender} takes over
 * outside production.
 *
 * <p>The text is unaccented: one SMS under 160 characters, and what a
 * brandname not registered for Unicode accepts.
 *
 * <p>Sent off the request thread. A number with an account gets an SMS, one
 * without does not; inline, the provider round trip would make the first
 * answer measurably slower and the recovery form would tell the two apart. The
 * price is that a refused send cannot reach the person: it is logged, and they
 * ask for a new code after the cooldown.
 */
@Component
@ConditionalOnExpression("!'${app.sms.esms.api-key:}'.isBlank()")
public class EsmsOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(EsmsOtpSender.class);

    // The JSON operation, not "_post" (which rejects a JSON body with 400); the
    // trailing slash matters, without it eSMS answers with a redirect.
    private static final String SEND_PATH = "/MainService.svc/json/SendMultipleMessage_V4_post_json/";
    /** The CodeResult for "accepted"; any other is a refusal. */
    private static final String ACCEPTED = "100";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;
    private final String apiKey;
    private final String secretKey;
    private final String brandname;
    private final String smsType;
    /** The registered content template, {@code {code}} for the code; blank for the default text. */
    private final String template;
    /** eSMS's test mode: answered like a real send, but not delivered and not charged. */
    private final boolean sandbox;
    private final long ttlMinutes;

    public EsmsOtpSender(@Value("${app.sms.esms.api-key}") String apiKey,
                         @Value("${app.sms.esms.secret-key:}") String secretKey,
                         @Value("${app.sms.esms.brandname:}") String brandname,
                         @Value("${app.sms.esms.sms-type:2}") String smsType,
                         @Value("${app.sms.esms.base-url:https://rest.esms.vn}") String baseUrl,
                         @Value("${app.sms.esms.template:}") String template,
                         @Value("${app.sms.esms.sandbox:false}") boolean sandbox,
                         OtpProperties properties) {
        // Fail at start-up, not on the first forgotten password: eSMS refuses
        // every message without these, and only the background log would say so.
        if (secretKey.isBlank()) {
            throw new IllegalStateException("ESMS_SECRET_KEY must be set together with ESMS_API_KEY");
        }
        if ("2".equals(smsType.trim()) && brandname.isBlank()) {
            throw new IllegalStateException("ESMS_BRANDNAME is required for SmsType 2 (customer-care brandname)");
        }
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .requestFactory(timeouts)
                .build();
        this.apiKey = apiKey.trim();
        this.secretKey = secretKey.trim();
        this.brandname = brandname.trim();
        this.smsType = smsType.trim();
        this.template = template.trim();
        this.sandbox = sandbox;
        if (!this.template.isEmpty() && !this.template.contains("{code}")) {
            throw new IllegalStateException("app.sms.esms.template must contain {code}");
        }
        this.ttlMinutes = Math.max(1, properties.codeTtl().toMinutes());
    }

    @Override
    public void send(String phone, String code, VerificationPurpose purpose) {
        Thread.ofVirtual().name("otp-sms").start(() -> {
            try {
                String error = deliver(phone, message(code, purpose));
                if (error == null && sandbox) {
                    // Nothing reaches the phone in sandbox mode, so the log is the only way to the code.
                    log.info("{} code for {}: {} (eSMS sandbox accepted it; not delivered)",
                            purpose, PhoneNumbers.mask(phone), code);
                } else if (error == null) {
                    log.info("{} code sent by SMS to {}", purpose, PhoneNumbers.mask(phone));
                } else {
                    log.error("eSMS refused {} code to {}: {}", purpose, PhoneNumbers.mask(phone), error);
                }
            } catch (RuntimeException e) {
                // Caught whole: on this thread nothing else would log it.
                log.error("Could not reach eSMS for {} code to {}: {}", purpose, PhoneNumbers.mask(phone), e.getMessage());
            }
        });
    }

    /**
     * One call to eSMS, on the calling thread.
     *
     * @return {@code null} when eSMS accepted the message, else why it did not
     * @throws RestClientException when eSMS could not be reached or answered
     *                             with an HTTP error
     */
    String deliver(String phone, String content) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("ApiKey", apiKey);
        body.put("SecretKey", secretKey);
        body.put("Phone", national(phone));
        body.put("Content", content);
        body.put("IsUnicode", "0");
        if (!brandname.isEmpty()) {
            body.put("Brandname", brandname);
        }
        body.put("SmsType", smsType);
        if (sandbox) {
            body.put("Sandbox", "1");
        }

        // Read as text and parsed here: eSMS does not always label its JSON as
        // application/json, and a redirect (POST is not followed) has none.
        ResponseEntity<String> response = client.post()
                .uri(SEND_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            String moved = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            return "HTTP " + response.getStatusCode().value() + (moved == null ? "" : " to " + moved + "; check ESMS_BASE_URL");
        }
        JsonNode answer = JSON.readTree(response.getBody() == null ? "{}" : response.getBody());
        String result = answer.path("CodeResult").asString("");
        if (ACCEPTED.equals(result)) {
            return null;
        }
        String reason = answer.path("ErrorMessage").asString("");
        return "CodeResult=" + result + (reason.isBlank() ? "" : " " + reason);
    }

    String message(String code, VerificationPurpose purpose) {
        // A brandname only carries the text it was registered with (eSMS's
        // trial one included), so a configured template wins over our own.
        if (!template.isEmpty()) {
            return template.replace("{code}", code);
        }
        return switch (purpose) {
            case REGISTER -> "VitalCare: ma xac thuc cua ban la %s, het han sau %d phut. Khong chia se ma nay."
                    .formatted(code, ttlMinutes);
            case RESET_PASSWORD -> ("VitalCare: ma dat lai mat khau cua ban la %s, het han sau %d phut. "
                    + "Neu ban khong yeu cau, hay bo qua tin nay.").formatted(code, ttlMinutes);
        };
    }

    /** eSMS takes the national form: +84912345678 becomes 0912345678. */
    static String national(String phone) {
        return phone.startsWith("+84") ? "0" + phone.substring(3) : phone;
    }
}
