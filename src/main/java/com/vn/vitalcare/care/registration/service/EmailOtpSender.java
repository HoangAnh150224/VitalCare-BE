package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends one-time codes by email through the SMTP account in {@code spring.mail.*}
 * (a Gmail account with an App Password, by default).
 *
 * <p>Sent off the request thread. SMTP takes a second or two; inline, a number
 * whose account has an email would answer measurably slower than one without,
 * and the recovery form would tell the two apart — the very thing it answers
 * alike to avoid. The price is that a failed send cannot reach the person: it
 * is logged, and they ask for a new code after the cooldown.
 */
@Component
@ConditionalOnExpression("!'${spring.mail.username:}'.isBlank()")
public class EmailOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpSender.class);

    private final JavaMailSender mail;
    private final String from;
    private final long ttlMinutes;

    public EmailOtpSender(JavaMailSender mail,
                          @Value("${app.mail.from}") String from,
                          OtpProperties properties) {
        this.mail = mail;
        this.from = from;
        this.ttlMinutes = Math.max(1, properties.codeTtl().toMinutes());
    }

    @Override
    public void send(String email, String code, VerificationPurpose purpose) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        String what = purpose == VerificationPurpose.RESET_PASSWORD ? "khôi phục mật khẩu" : "xác thực";
        message.setSubject("[VitalCare] Mã " + what + ": " + code);
        message.setText("""
                Xin chào,

                Mã %s VitalCare của bạn là: %s
                Mã có hiệu lực trong %d phút. Không chia sẻ mã này cho bất kỳ ai.

                Nếu bạn không yêu cầu mã này, hãy bỏ qua email.
                """.formatted(what, code, ttlMinutes));

        Thread.ofVirtual().name("otp-mail").start(() -> {
            try {
                mail.send(message);
            } catch (RuntimeException e) {
                // Caught whole: on this thread nothing else would log it.
                log.error("Could not email a {} code to {}: {}", purpose, LoggingOtpSender.mask(email), e.getMessage());
            }
        });
    }
}
