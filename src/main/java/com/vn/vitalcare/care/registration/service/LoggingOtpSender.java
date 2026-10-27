package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Writes the code to the application log instead of sending it.
 *
 * <p>For development without an SMS account, so password recovery can be
 * exercised end to end. Anybody who can read the log can finish any password
 * reset, so this must never face real users. The number is masked; the code
 * cannot be, or there would be no point.
 *
 * <p>Off under the {@code prod} profile, so a production deployment without
 * SMS fails to start — no {@link OtpSender} bean — rather than quietly
 * writing every one-time code into its logs.
 */
@Component
@Profile("!prod")
// Stands aside as soon as eSMS is configured (EsmsOtpSender).
@ConditionalOnExpression("'${app.sms.esms.api-key:}'.isBlank()")
public class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String phone, String code, VerificationPurpose purpose) {
        log.info("{} code for {}: {} (simulated delivery, no SMS sent)", purpose, PhoneNumbers.mask(phone), code);
    }
}
