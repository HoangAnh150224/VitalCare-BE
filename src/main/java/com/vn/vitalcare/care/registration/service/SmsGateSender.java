package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Every SMS this system sends — one-time codes, confirmations, emergency
 * texts — goes out through SMSGate: an Android phone with a SIM, reached
 * through SMSGate's cloud (or a private server: only the URL differs). The
 * account is in {@code app.sms.gate.*}, from the environment only. Active
 * whenever {@code SMSGATE_USERNAME} is set; without it
 * {@link LoggingOtpSender} takes over outside production.
 *
 * <p>The texts are unaccented, so each fits one SMS of 160 characters.
 *
 * <p>The gateway only queues the message for the phone: "accepted" here means
 * queued, not delivered. A phone that is off or out of credit shows in the
 * SMSGate app, not in this answer.
 */
@Component
@ConditionalOnExpression("!'${app.sms.gate.username:}'.isBlank()")
public class SmsGateSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(SmsGateSender.class);

    private final RestClient client;
    private final String url;
    private final long ttlMinutes;

    public SmsGateSender(@Value("${app.sms.gate.url}") String url,
                         @Value("${app.sms.gate.username}") String username,
                         @Value("${app.sms.gate.password:}") String password,
                         OtpProperties properties) {
        // Fail at start-up, not on the first forgotten password: the gateway refuses every message without it.
        if (password.isBlank()) {
            throw new IllegalStateException("SMSGATE_PASSWORD must be set together with SMSGATE_USERNAME");
        }
        // The JDK's HttpClient, not HttpURLConnection: that one drops the body of a 401, and with it the reason.
        JdkClientHttpRequestFactory timeouts = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        timeouts.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder().requestFactory(timeouts)
                .defaultHeaders(h -> h.setBasicAuth(username.trim(), password.trim()))
                .build();
        this.url = url.trim();
        this.ttlMinutes = Math.max(1, properties.codeTtl().toMinutes());
    }

    /** Off the request thread, so the gateway round trip does not hold the API response open. */
    @Override
    public void send(String phone, String code, VerificationPurpose purpose) {
        Thread.ofVirtual().name("otp-sms").start(() -> {
            try {
                String error = deliver(phone, message(code, purpose));
                if (error == null) {
                    log.info("{} code sent by SMS to {}", purpose, PhoneNumbers.mask(phone));
                } else {
                    log.error("SMSGate refused {} code to {}: {}", purpose, PhoneNumbers.mask(phone), error);
                }
            } catch (RuntimeException e) {
                // Caught whole: on this thread nothing else would log it.
                log.error("Could not reach SMSGate for {} code to {}: {}", purpose, PhoneNumbers.mask(phone),
                        e.getClass().getSimpleName());
            }
        });
    }

    /**
     * One call to the gateway, on the calling thread.
     *
     * @return {@code null} when the gateway queued the message, else why it did not
     * @throws RestClientException when the gateway could not be reached
     */
    public String deliver(String phone, String content) {
        Map<String, Object> body = Map.of(
                "textMessage", Map.of("text", content),
                "phoneNumbers", List.of(PhoneNumbers.normalize(phone).orElse(phone)));
        try {
            client.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
            return null;
        } catch (RestClientResponseException e) {
            String said = e.getResponseBodyAsString().replaceAll("\\s+", " ").trim();
            return "HTTP " + e.getStatusCode().value() + (said.isEmpty() ? "" : " " + (said.length() <= 200 ? said : said.substring(0, 200)));
        }
    }

    String message(String code, VerificationPurpose purpose) {
        return switch (purpose) {
            case REGISTER -> "VitalCare: ma xac thuc cua ban la %s, het han sau %d phut. Khong chia se ma nay."
                    .formatted(code, ttlMinutes);
            case RESET_PASSWORD -> ("VitalCare: ma dat lai mat khau cua ban la %s, het han sau %d phut. "
                    + "Neu ban khong yeu cau, hay bo qua tin nay.").formatted(code, ttlMinutes);
        };
    }
}
