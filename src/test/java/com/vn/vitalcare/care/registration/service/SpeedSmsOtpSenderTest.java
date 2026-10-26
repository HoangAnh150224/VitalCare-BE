package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The default brandname drops diacritics and bills per 160 characters: every message stays one plain SMS. */
class SpeedSmsOtpSenderTest {

    @Test
    void everyMessageIsOnePlainSms() {
        OtpProperties properties = new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5);
        SpeedSmsOtpSender sender = new SpeedSmsOtpSender("token", "http://localhost:1", "", properties);
        for (VerificationPurpose purpose : VerificationPurpose.values()) {
            String text = sender.message("123456", purpose);
            assertTrue(text.length() <= 160, purpose + " is " + text.length() + " characters");
            assertTrue(text.chars().allMatch(c -> c < 128), purpose + " has a non-ASCII character");
            assertTrue(text.contains("123456") && text.contains("5 phut"), text);
        }
    }
}
