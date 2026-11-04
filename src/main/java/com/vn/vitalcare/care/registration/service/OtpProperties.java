package com.vn.vitalcare.care.registration.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * One-time code settings, bound from {@code app.otp.*}.
 *
 * @param codeLength     digits in a code
 * @param codeTtl        how long a code may be used after it is sent
 * @param maxAttempts    wrong guesses before the code is burned
 * @param resendCooldown how long after one code another may be requested
 * @param maxPerHour     codes one number may be sent in any rolling hour
 */
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        int codeLength,
        Duration codeTtl,
        int maxAttempts,
        Duration resendCooldown,
        int maxPerHour) {
}
