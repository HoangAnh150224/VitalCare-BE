package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Writes the code to the application log instead of sending it.
 *
 * <p>For development without a mail account, so password recovery can be
 * exercised end to end. Anybody who can read the log can finish any password
 * reset, so this must never face real users. The address is masked; the code
 * cannot be, or there would be no point.
 *
 * <p>Off under the {@code prod} profile, so a production deployment without
 * mail fails to start — no {@link OtpSender} bean — rather than quietly
 * writing every one-time code into its logs.
 */
@Component
@Profile("!prod")
// Stands aside as soon as mail is configured (EmailOtpSender).
@ConditionalOnExpression("'${spring.mail.username:}'.isBlank()")
public class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String email, String code, VerificationPurpose purpose) {
        log.info("{} code for {}: {} (simulated delivery, no email sent)", purpose, mask(email), code);
    }

    /** {@code an***@gmail.com}: enough to tell test accounts apart in the log. */
    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 2 ? "***" + email.substring(Math.max(at, 0)) : email.substring(0, 2) + "***" + email.substring(at);
    }
}
