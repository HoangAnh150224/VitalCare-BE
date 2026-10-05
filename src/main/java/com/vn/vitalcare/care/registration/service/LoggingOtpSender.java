package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.share.phone.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Writes the code to the application log instead of sending it.
 *
 * <p>A stand-in until an SMS provider is wired up, so that registration can be
 * exercised end to end. Anybody who can read the log can finish any
 * registration, so this must be replaced before the application faces real
 * users. The number is masked; the code cannot be, or there would be no point.
 *
 * <p>Off under the {@code prod} profile, so a production deployment without a
 * real sender fails to start — no {@link OtpSender} bean — rather than quietly
 * writing every registration code into its logs.
 */
@Component
@Profile("!prod")
public class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String phone, String code) {
        log.info("Registration code for {}: {} (simulated delivery, no SMS sent)", PhoneNumbers.mask(phone), code);
    }
}
