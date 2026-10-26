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
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One-time codes sent to a phone number, for whichever flow needs the number
 * proved: self-registration, and recovering a forgotten password.
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
    /** Present when Firebase sends the codes; then {@link #prove} takes its token, never a code of ours. */
    private final Optional<FirebasePhoneTokens> firebase;
    private final SecureRandom random = new SecureRandom();

    public PhoneCodes(PhoneVerificationRepository verifications,
                      OtpSender sender,
                      OtpProperties properties,
                      Clock clock) {
        this(verifications, sender, properties, clock, Optional.empty());
    }

    @Autowired
    public PhoneCodes(PhoneVerificationRepository verifications,
                      Optional<OtpSender> sender,
                      OtpProperties properties,
                      Clock clock,
                      Optional<FirebasePhoneTokens> firebase) {
        this(verifications, sender.orElse(null), properties, clock, firebase);
    }

    private PhoneCodes(PhoneVerificationRepository verifications,
                       OtpSender sender,
                       OtpProperties properties,
                       Clock clock,
                       Optional<FirebasePhoneTokens> firebase) {
        if (sender == null && firebase.isEmpty()) {
            // Production with neither route configured: refuse to start rather
            // than issue codes nobody receives (LoggingOtpSender is off there).
            throw new IllegalStateException(
                    "No way to send one-time codes: set APP_FIREBASE_PROJECT_ID or an SMS sender");
        }
        this.verifications = verifications;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
        this.firebase = firebase;
    }

    /** Test seam: as Spring builds it with Firebase switched on. */
    static PhoneCodes withFirebase(PhoneVerificationRepository verifications, OtpProperties properties, Clock clock,
                                   FirebasePhoneTokens firebase) {
        return new PhoneCodes(verifications, (OtpSender) null, properties, clock, Optional.of(firebase));
    }

    /**
     * Records a new code for a number and, if {@code deliver}, sends it.
     *
     * <p>Not delivering still records the code and still applies both limits.
     * That is what lets password recovery answer a number with no account
     * exactly as it answers one with an account: same cooldown, same cap, same
     * reply — only no message goes out.
     *
     * @param phone the number, already normalised to E.164
     */
    public OtpResponse issue(String phone, VerificationPurpose purpose, boolean deliver) {
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
        // With Firebase the screen has Google send the SMS; the row above still
        // counts towards the cooldown and the hourly cap.
        if (deliver && firebase.isEmpty()) {
            // ponytail: sent inline, which is free with LoggingOtpSender. A
            // real SMS provider would make a number with an account answer
            // slower — and fail where a stranger's succeeds — which tells the
            // two apart; send after commit, asynchronously, when one is wired.
            sender.send(phone, code, purpose);
        }

        return new OtpResponse(properties.codeTtl().toSeconds(), properties.resendCooldown().toSeconds(),
                firebase.isPresent() ? "firebase" : "sms");
    }

    /**
     * Proves the person holds the number: Firebase's token when Firebase sends
     * the codes, otherwise our code. The two never stand in for each other — a
     * code of ours is sent nowhere while Firebase is on.
     *
     * @throws InvalidVerificationCodeException wrong, expired or for another number
     * @throws FieldValidationException         the proof this mode needs is missing
     */
    public void prove(String phone, VerificationPurpose purpose, String otp, String firebaseToken) {
        if (firebase.isPresent()) {
            if (firebaseToken == null || firebaseToken.isBlank()) {
                throw new FieldValidationException("otp", "The code is required");
            }
            if (!phone.equals(firebase.get().verifiedPhone(firebaseToken))) {
                throw new InvalidVerificationCodeException();
            }
            // The token stays valid for minutes; the request it answers is
            // spent here, so one token makes one account or one reset — and a
            // flow that skipped the request (and its limits) gets nowhere.
            Instant now = clock.instant();
            PhoneVerification request = verifications.findLatestForUpdate(phone, purpose)
                    .filter(candidate -> candidate.isUsable(now, properties.maxAttempts()))
                    .orElseThrow(InvalidVerificationCodeException::new);
            request.consume(now);
            verifications.save(request);
            return;
        }
        if (otp == null || otp.isBlank()) {
            throw new FieldValidationException("otp", "The code is required");
        }
        verify(phone, purpose, otp);
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
