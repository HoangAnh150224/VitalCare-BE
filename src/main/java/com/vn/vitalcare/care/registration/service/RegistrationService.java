package com.vn.vitalcare.care.registration.service;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.entity.PhoneVerification;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.repository.PhoneVerificationRepository;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.auth.service.AuthService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-registration: prove the phone number, then create the account and its
 * neutral customer record, then sign the person in.
 *
 * <p>Lives with the care domain rather than identity because what it creates
 * is a customer; it uses identity's services to make the account behind one,
 * and identity knows nothing about it.
 */
@Service
@Transactional(readOnly = true)
public class RegistrationService {

    /** The system role every self-registered account receives; seeded by 013. */
    static final String CUSTOMER_ROLE = "CUSTOMER";

    /** BCrypt ignores — and the encoder refuses — anything past this many bytes. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final PhoneVerificationRepository verifications;
    private final UserService userService;
    private final CustomerService customerService;
    private final AuthService authService;
    private final OtpSender sender;
    private final OtpProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RegistrationService(PhoneVerificationRepository verifications,
                               UserService userService,
                               CustomerService customerService,
                               AuthService authService,
                               OtpSender sender,
                               OtpProperties properties,
                               Clock clock) {
        this.verifications = verifications;
        this.userService = userService;
        this.customerService = customerService;
        this.authService = authService;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Sends a registration code to a number.
     *
     * <p>A number that already has an account is refused outright. That does
     * tell the caller the number is registered — unavoidable on a sign-up form,
     * which has to say why it will not proceed — and nothing here limits how
     * fast that question can be asked: the resend limits below only meter
     * codes actually sent. Bounding it needs a per-client rate limit in front
     * of the endpoint, which this application does not have yet.
     *
     * <p>The newest code row is locked while the limits are checked, so two
     * requests in parallel cannot both slip under the cooldown. The very first
     * request for a number has no row to lock; two of those racing can send
     * two codes, of which only the newer is ever accepted.
     */
    @Transactional
    public OtpResponse requestCode(String rawPhone) {
        String phone = normalize(rawPhone);
        if (userService.phoneInUse(phone)) {
            throw new ConflictException("That phone number is already in use");
        }

        Instant now = clock.instant();
        Optional<PhoneVerification> latest = latestLocked(phone);
        if (latest.isPresent()) {
            Instant allowedAt = latest.get().getCreatedAt().plus(properties.resendCooldown());
            if (allowedAt.isAfter(now)) {
                long wait = Math.max(1, Duration.between(now, allowedAt).toSeconds());
                throw new ConflictException("Please wait %d seconds before asking for another code".formatted(wait));
            }
        }
        long sentLastHour = verifications.countByPhoneAndPurposeAndCreatedAtAfter(
                phone, VerificationPurpose.REGISTER, now.minus(Duration.ofHours(1)));
        if (sentLastHour >= properties.maxPerHour()) {
            throw new ConflictException("Too many codes have been sent to this number; try again later");
        }

        String code = generateCode();
        verifications.save(new PhoneVerification(
                phone, VerificationPurpose.REGISTER, hash(phone, code), now, now.plus(properties.codeTtl())));
        sender.send(phone, code);

        return new OtpResponse(properties.codeTtl().toSeconds(), properties.resendCooldown().toSeconds());
    }

    /**
     * Checks the code and, if it holds, creates the account, its neutral
     * customer record and a session — all or nothing.
     *
     * <p>A wrong code is the one failure that must <em>not</em> roll back: the
     * attempt it records is what burns the code after the configured number
     * of guesses. Nothing else has been written by then, so letting that
     * transaction commit keeps only the counter.
     *
     * <p>The code row is read under a lock, so guesses sent in parallel queue
     * on it and each one is counted; see
     * {@link PhoneVerificationRepository#findLatestForUpdate}.
     */
    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public TokenResponse register(RegisterRequest request) {
        String phone = normalize(request.phone());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new FieldValidationException("password", "Password is too long; use fewer accented letters");
        }

        Instant now = clock.instant();
        PhoneVerification verification = latestLocked(phone)
                .filter(candidate -> candidate.isUsable(now, properties.maxAttempts()))
                .orElseThrow(InvalidVerificationCodeException::new);

        if (!MessageDigest.isEqual(
                verification.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                hash(phone, request.otp().trim()).getBytes(StandardCharsets.US_ASCII))) {
            verification.recordFailedAttempt();
            verifications.save(verification);
            throw new InvalidVerificationCodeException();
        }
        verification.consume(now);
        verifications.save(verification);

        User user = userService.createSelfRegistered(phone, request.password(), request.fullName(), CUSTOMER_ROLE);
        customerService.createNeutral(user);
        return authService.signInVerified(user);
    }

    private Optional<PhoneVerification> latestLocked(String phone) {
        return verifications.findLatestForUpdate(phone, VerificationPurpose.REGISTER);
    }

    /** Unreachable through HTTP: the DTO pattern only admits numbers that normalise. */
    private static String normalize(String raw) {
        return PhoneNumbers.normalize(raw)
                .orElseThrow(() -> new FieldValidationException("phone", "Not a valid Vietnamese phone number"));
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
