package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.entity.PhoneVerification;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.repository.PhoneVerificationRepository;
import com.vn.vitalcare.share.exception.ConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * One-time codes for the phone number an account signs in with, sent to that
 * number by SMS: recovering a forgotten password.
 *
 * <p>A code belongs to the number — that is what the person types and what
 * the limits count.
 *
 * <p>Holds no transaction of its own; it runs inside the caller's, so the
 * newest code row stays locked until that flow is done with it. See
 * {@link PhoneVerificationRepository#findLatestForUpdate} for why the lock
 * matters on both sending and checking.
 */
@Component
public class PhoneCodes {

    private final PhoneVerificationRepository verifications;
    private final OtpSender sender;
    private final OtpProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PhoneCodes(PhoneVerificationRepository verifications,
                      OtpSender sender,
                      OtpProperties properties,
                      Clock clock) {
        this.verifications = verifications;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records a new code for a number and, if asked, sends it.
     *
     * <p>Not sending still records the code and still applies both limits.
     * That is what lets password recovery answer a number with no account
     * exactly as it answers one that gets the code: same cooldown, same cap,
     * same reply; only no message goes out.
     *
     * @param phone     the number, already normalised to E.164
     * @param deliverTo the number to send the code to, or {@code null} for none
     */
    public OtpResponse issue(String phone, VerificationPurpose purpose, String deliverTo) {
        Instant now = clock.instant();
        Optional<PhoneVerification> latest = verifications.findLatestForUpdate(phone, purpose);
        if (latest.isPresent()) {
            Instant allowedAt = latest.get().getCreatedAt().plus(properties.resendCooldown());
            if (allowedAt.isAfter(now)) {
                long wait = Math.max(1, Duration.between(now, allowedAt).toSeconds());
                throw new ConflictException("Please wait %d seconds before asking for another code".formatted(wait));
            }
        }
        long sentLastHour = verifications.countByPhoneAndPurposeAndCreatedAtAfter(
                phone, purpose, now.minus(Duration.ofHours(1)));
        if (sentLastHour >= properties.maxPerHour()) {
            throw new ConflictException("Too many codes have been sent to this number; try again later");
        }

        String code = generateCode();
        verifications.save(new PhoneVerification(
                phone, purpose, hash(phone, code), now, now.plus(properties.codeTtl())));
        if (deliverTo != null) {
            sender.send(deliverTo, code, purpose);
        }

        return new OtpResponse(properties.codeTtl().toSeconds(), properties.resendCooldown().toSeconds());
    }

    /**
     * Accepts the newest code for this number and purpose, and spends it.
     *
     * <p>A wrong guess is counted before the exception is thrown, so the
     * caller's transaction must not roll back on
     * {@link InvalidVerificationCodeException}; that counter is what burns the
     * code after the configured number of guesses.
     *
     * @throws InvalidVerificationCodeException wrong, expired, spent or burned
     *                                          — one answer for all four
     */
    public void verify(String phone, VerificationPurpose purpose, String code) {
        Instant now = clock.instant();
        PhoneVerification verification = verifications.findLatestForUpdate(phone, purpose)
                .filter(candidate -> candidate.isUsable(now, properties.maxAttempts()))
                .orElseThrow(InvalidVerificationCodeException::new);

        if (!MessageDigest.isEqual(
                verification.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                hash(phone, code.trim()).getBytes(StandardCharsets.US_ASCII))) {
            verification.recordFailedAttempt();
            verifications.save(verification);
            throw new InvalidVerificationCodeException();
        }
        verification.consume(now);
        verifications.save(verification);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, properties.codeLength());
        return String.format("%0" + properties.codeLength() + "d", random.nextInt(bound));
    }

    /**
     * Bound to the number, so a code's hash means nothing for any other one.
     * See {@code 011-create-phone-verification} for what hashing a six-digit
     * code does and does not protect.
     */
    private static String hash(String phone, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
