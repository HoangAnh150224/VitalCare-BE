package com.vn.vitalcare.it;

import com.vn.vitalcare.care.registration.service.OtpSender;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The SMS gateway is the one thing outside the system under test: this stands in for it and keeps the last code
 * texted to each number, so a test can type it like a person reading the SMS would. Everything up to the gateway
 * (code generation, hashing, storage, limits, checking) is the real code.
 */
public class TextedCodes implements OtpSender {

    private final Map<String, String> last = new ConcurrentHashMap<>();
    private final Map<String, VerificationPurpose> purpose = new ConcurrentHashMap<>();

    @Override
    public void send(String phone, String code, VerificationPurpose purpose) {
        last.put(phone, code);
        this.purpose.put(phone, purpose);
    }

    /** The last code texted to a number, given as 09… or +84…; null when none was sent. */
    public String last(String phone) {
        return last.get(e164(phone));
    }

    public VerificationPurpose purposeOf(String phone) {
        return purpose.get(e164(phone));
    }

    public void forget(String phone) {
        last.remove(e164(phone));
        purpose.remove(e164(phone));
    }

    private static String e164(String phone) {
        return phone.startsWith("0") ? "+84" + phone.substring(1) : phone;
    }

    @TestConfiguration
    public static class Gateway {
        @Bean
        @Primary
        TextedCodes textedCodes() {
            return new TextedCodes();
        }
    }
}
