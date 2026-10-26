package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends the one-time code by SMS through SpeedSMS (speedsms.vn), on its
 * default brandname: no brandname of our own to register, so no business
 * licence needed. Active whenever {@code APP_SMS_SPEEDSMS_TOKEN} is set;
 * without it {@link LoggingOtpSender} takes over outside production.
 *
 * <p>The default brandname does not carry Vietnamese diacritics, hence the
 * unaccented text. One message is one SMS: under 160 characters.
 *
 * <p>A failed send throws, which rolls the issued code back: the person can
 * ask again at once instead of waiting out a cooldown for a code that never
 * arrived.
 */
@Component
@ConditionalOnExpression("!'${app.sms.speedsms.token:}'.isBlank()")
public class SpeedSmsOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(SpeedSmsOtpSender.class);
    /** {@code sms_type} 4: the provider's default brandname. */
    private static final int DEFAULT_BRANDNAME = 4;

    private final RestClient client;
    private final OtpProperties properties;
    /** The brandname to send under; blank leaves it to the account's default. */
    private final String sender;

    public SpeedSmsOtpSender(@Value("${app.sms.speedsms.token}") String token,
                             @Value("${app.sms.speedsms.url:https://api.speedsms.vn/index.php}") String url,
                             @Value("${app.sms.speedsms.sender:}") String sender,
                             OtpProperties properties) {
        this.sender = sender.trim();
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        // The access token is the Basic-auth user; the password is ignored.
        String basic = Base64.getEncoder().encodeToString((token.trim() + ":x").getBytes(StandardCharsets.UTF_8));
        this.client = RestClient.builder()
                .baseUrl(url)
                .requestFactory(timeouts)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .build();
        this.properties = properties;
    }

    @Override
    public void send(String phone, String code, VerificationPurpose purpose) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                // E.164 without the plus: 84912345678.
                "to", List.of(phone.startsWith("+") ? phone.substring(1) : phone),
                "content", message(code, purpose),
                "sms_type", DEFAULT_BRANDNAME));
        if (!sender.isEmpty()) {
            body.put("sender", sender);
        }
        Map<?, ?> answer;
        try {
            answer = client.post()
                    .uri("/sms/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.error("SpeedSMS unreachable sending {} code to {}: {}", purpose, PhoneNumbers.mask(phone), e.getMessage());
            throw notSent();
        }
        if (answer == null || !"success".equals(answer.get("status"))) {
            // 300 is an empty balance; the rest are in SpeedSMS's API page.
            log.error("SpeedSMS refused {} code to {}: code={} message={}", purpose, PhoneNumbers.mask(phone),
                    answer == null ? null : answer.get("code"), answer == null ? null : answer.get("message"));
            throw notSent();
        }
        log.info("{} code sent by SMS to {}", purpose, PhoneNumbers.mask(phone));
    }

    String message(String code, VerificationPurpose purpose) {
        long minutes = properties.codeTtl().toMinutes();
        return switch (purpose) {
            case REGISTER -> "VitalCare: ma xac thuc dang ky cua ban la %s, het han sau %d phut. Khong chia se ma nay."
                    .formatted(code, minutes);
            case RESET_PASSWORD -> ("VitalCare: ma dat lai mat khau cua ban la %s, het han sau %d phut. "
                    + "Neu ban khong yeu cau, hay bo qua tin nay.").formatted(code, minutes);
        };
    }

    private static ConflictException notSent() {
        return new ConflictException("The code could not be sent right now; try again in a minute");
    }
}
