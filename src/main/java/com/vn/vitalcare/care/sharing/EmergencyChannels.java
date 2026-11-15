package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.care.registration.service.EsmsOtpSender;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * How an emergency reaches a phone (BF-06 Phone-call Escalation). The call is
 * placed through Twilio, which rings the number from the Twilio number of the
 * account and reads the alert aloud in Vietnamese, when
 * {@code TWILIO_ACCOUNT_SID}, {@code TWILIO_AUTH_TOKEN} and
 * {@code TWILIO_FROM_NUMBER} are all set. Without them the call is simulated.
 * A Twilio trial account only rings numbers verified on it, and speaks a short
 * English notice first. The SMS goes through eSMS when it is configured
 * ({@code ESMS_API_KEY}), and is only logged otherwise.
 */
@Component
public class EmergencyChannels {

    private static final Logger log = LoggerFactory.getLogger(EmergencyChannels.class);

    /** What came of one attempt: {@code outcome} as stored in the call log, and a line of detail. */
    public record Outcome(String outcome, String detail) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Twilio's own helper that answers with the TwiML it is given in the query string. */
    private static final String TWIML_ECHO = "https://twimlets.com/echo?Twiml=";

    private final ObjectProvider<EsmsOtpSender> esms;
    private final RestClient twilio;
    private final String accountSid;
    private final String fromNumber;
    private final String voice;
    private final boolean callsConfigured;

    public EmergencyChannels(ObjectProvider<EsmsOtpSender> esms,
                             @Value("${app.call.twilio.account-sid:}") String accountSid,
                             @Value("${app.call.twilio.auth-token:}") String authToken,
                             @Value("${app.call.twilio.from-number:}") String fromNumber,
                             @Value("${app.call.twilio.voice:Google.vi-VN-Standard-A}") String voice,
                             @Value("${app.call.twilio.base-url:https://api.twilio.com}") String baseUrl) {
        this.esms = esms;
        this.accountSid = accountSid.trim();
        this.fromNumber = e164(fromNumber.trim());
        this.voice = voice.trim();
        this.callsConfigured = !this.accountSid.isEmpty() && !authToken.isBlank() && !this.fromNumber.isEmpty();
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(15));
        this.twilio = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts)
                .defaultHeaders(h -> h.setBasicAuth(this.accountSid, authToken.trim()))
                .build();
    }

    public Outcome call(String phone, String message) {
        if (!callsConfigured) {
            log.warn("EMERGENCY CALL (simulated) to {}: {}", PhoneNumbers.mask(phone), message);
            return new Outcome("SIMULATED", "Cuộc gọi giả lập: chưa cấu hình Twilio (TWILIO_ACCOUNT_SID, "
                    + "TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER), đã ghi nhận đổ chuông tới " + phone);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", e164(phone));
        form.add("From", fromNumber);
        // ponytail: the script goes by URL through Twilio's echo twimlet because a trial account refuses the
        // inline Twiml parameter (and anything beyond To, From and Url). On an upgraded account send "Twiml" directly and drop the detour.
        // Spaces as %20, not "+": the form the call made from Twilio's console used, and the one that was read aloud.
        form.add("Url", TWIML_ECHO + URLEncoder.encode(twiml(message), StandardCharsets.UTF_8).replace("+", "%20"));
        try {
            String body = twilio.post()
                    .uri("/2010-04-01/Accounts/{sid}/Calls.json", accountSid)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            // Accepted, not yet answered: Twilio reports the pick-up only to a status callback.
            return new Outcome("PLACED", "Twilio đã nhận cuộc gọi, mã "
                    + JSON.readTree(body == null ? "{}" : body).path("sid").asString(""));
        } catch (RestClientResponseException e) {
            // Twilio says why in the body: an unverified number on a trial account, no credit, a bad number.
            return new Outcome("FAILED", "Twilio từ chối cuộc gọi: " + refusal(e.getResponseBodyAsString()));
        } catch (RuntimeException e) {
            log.error("Twilio call to {} failed: {}", PhoneNumbers.mask(phone), e.getClass().getSimpleName());
            return new Outcome("FAILED", "Không gọi được Twilio (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** What the call says: the alert, read twice because the first seconds after picking up are easily missed. */
    String twiml(String message) {
        String text = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<Response><Say language=\"vi-VN\" voice=\"" + voice + "\" loop=\"2\">" + text + "</Say></Response>";
    }

    private static String refusal(String body) {
        try {
            JsonNode answer = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
            return "mã " + answer.path("code").asString("?") + " " + answer.path("message").asString("");
        } catch (RuntimeException e) {
            return "không đọc được phản hồi";
        }
    }

    /** Twilio takes E.164: 0912345678 and 84912345678 become +84912345678. */
    static String e164(String phone) {
        if (phone.isEmpty() || phone.startsWith("+")) {
            return phone;
        }
        return phone.startsWith("0") ? "+84" + phone.substring(1) : "+" + phone;
    }

    public Outcome sms(String phone, String text) {
        EsmsOtpSender sender = esms.getIfAvailable();
        if (sender == null) {
            log.warn("EMERGENCY SMS (eSMS not configured) to {}: {}", PhoneNumbers.mask(phone), text);
            return new Outcome("NOT_SENT", "Chưa cấu hình eSMS (ESMS_API_KEY); nội dung đã ghi vào log");
        }
        try {
            String error = sender.deliver(phone, text);
            return error == null ? new Outcome("SENT", "eSMS đã nhận tin")
                    : new Outcome("FAILED", "eSMS từ chối: " + error);
        } catch (RuntimeException e) {
            return new Outcome("FAILED", "Không gọi được eSMS: " + e.getMessage());
        }
    }
}
