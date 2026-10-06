package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.entity.PhoneVerification;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.repository.PhoneVerificationRepository;
import com.vn.vitalcare.care.registration.service.impl.RegistrationServiceImpl;
import com.vn.vitalcare.identity.auth.service.AuthService;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guarantees self-registration rests on: a number is only ever registered
 * by whoever received the code sent to it, and the code cannot be walked.
 */
class RegistrationServiceTest {

    private static final String PHONE = "+84912345678";
    private static final Instant START = Instant.parse("2026-10-05T03:00:00Z");

    private PhoneVerificationRepository verifications;
    private UserService userService;
    private CustomerService customerService;
    private AuthService authService;
    private MutableClock clock;
    private RegistrationService service;

    /** The code the last requestCode() "sent", read off the sender. */
    private final AtomicReference<String> sentCode = new AtomicReference<>();
    /** The row the last requestCode() saved, served back as the latest. */
    private final AtomicReference<PhoneVerification> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        verifications = mock(PhoneVerificationRepository.class);
        userService = mock(UserService.class);
        customerService = mock(CustomerService.class);
        authService = mock(AuthService.class);
        clock = new MutableClock(START);

        when(verifications.save(any())).thenAnswer(invocation -> {
            PhoneVerification row = invocation.getArgument(0);
            stored.set(row);
            return row;
        });
        when(verifications.findLatestForUpdate(PHONE, VerificationPurpose.REGISTER))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));

        OtpSender sender = (phone, code) -> sentCode.set(code);
        service = new RegistrationServiceImpl(
                verifications, userService, customerService, authService, sender,
                new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5),
                clock);
    }

    @Test
    @DisplayName("a code is sent, and only its hash is stored")
    void requestCodeStoresOnlyAHash() {
        service.requestCode("0912 345 678");

        String code = sentCode.get();
        assertNotNull(code);
        assertTrue(code.matches("\\d{6}"), "code: " + code);
        assertEquals(PHONE, stored.get().getPhone());
        assertFalse(stored.get().getCodeHash().contains(code));
    }

    @Test
    @DisplayName("a number that already has an account gets no code")
    void takenNumberIsRefused() {
        when(userService.phoneInUse(PHONE)).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.requestCode(PHONE));
        assertEquals(null, sentCode.get());
    }

    @Test
    @DisplayName("a resend inside the cooldown is refused; after it, allowed")
    void resendCooldown() {
        service.requestCode(PHONE);
        clock.advance(Duration.ofSeconds(30));
        assertThrows(ConflictException.class, () -> service.requestCode(PHONE));

        clock.advance(Duration.ofSeconds(31));
        service.requestCode(PHONE);
    }

    @Test
    @DisplayName("no more than the hourly cap of codes per number")
    void hourlyCap() {
        when(verifications.countByPhoneAndPurposeAndCreatedAtAfter(eq(PHONE), eq(VerificationPurpose.REGISTER), any()))
                .thenReturn(5L);

        assertThrows(ConflictException.class, () -> service.requestCode(PHONE));
    }

    @Test
    @DisplayName("the right code creates the account, a neutral customer and a session")
    void rightCodeRegisters() {
        service.requestCode(PHONE);

        service.register(request(sentCode.get()));

        verify(userService).createSelfRegistered(PHONE, "Passw0rd!", "Nguyen Van A", RegistrationServiceImpl.CUSTOMER_ROLE);
        verify(customerService).createNeutral(any());
        verify(authService).signInVerified(any());
        assertNotNull(stored.get().getConsumedAt());
    }

    @Test
    @DisplayName("a wrong code counts an attempt and creates nothing")
    void wrongCodeCountsAnAttempt() {
        service.requestCode(PHONE);

        assertThrows(InvalidVerificationCodeException.class, () -> service.register(request(wrong(sentCode.get()))));

        assertEquals(1, stored.get().getAttempts());
        verify(userService, never()).createSelfRegistered(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("after the last allowed wrong guess, even the right code is refused")
    void codeIsBurnedAfterMaxAttempts() {
        service.requestCode(PHONE);
        String code = sentCode.get();

        for (int i = 0; i < 5; i++) {
            assertThrows(InvalidVerificationCodeException.class, () -> service.register(request(wrong(code))));
        }
        assertThrows(InvalidVerificationCodeException.class, () -> service.register(request(code)));
        verify(userService, never()).createSelfRegistered(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an expired code is refused")
    void expiredCodeIsRefused() {
        service.requestCode(PHONE);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThrows(InvalidVerificationCodeException.class, () -> service.register(request(sentCode.get())));
    }

    @Test
    @DisplayName("a code cannot be used twice")
    void consumedCodeCannotBeReused() {
        service.requestCode(PHONE);
        String code = sentCode.get();
        service.register(request(code));

        assertThrows(InvalidVerificationCodeException.class, () -> service.register(request(code)));
    }

    private static RegisterRequest request(String otp) {
        return new RegisterRequest("0912345678", otp, "Nguyen Van A", "Passw0rd!");
    }

    /** A code guaranteed to differ from {@code code} in its last digit. */
    private static String wrong(String code) {
        char last = code.charAt(code.length() - 1);
        return code.substring(0, code.length() - 1) + (last == '9' ? '0' : (char) (last + 1));
    }

    /** A clock a test can move forward, so expiry and cooldown need no waiting. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
