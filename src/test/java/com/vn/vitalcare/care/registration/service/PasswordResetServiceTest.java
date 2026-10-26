package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.PasswordResetRequest;
import com.vn.vitalcare.care.registration.entity.PhoneVerification;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.repository.PhoneVerificationRepository;
import com.vn.vitalcare.care.registration.service.impl.PasswordResetServiceImpl;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guarantees password recovery rests on: only whoever receives the code
 * sent to the number can set a new password, and the form says nothing about
 * which numbers have accounts.
 */
class PasswordResetServiceTest {

    private static final String PHONE = "+84912345678";
    private static final String STRANGER = "+84987654321";
    private static final Instant START = Instant.parse("2026-10-21T03:00:00Z");

    private PhoneVerificationRepository verifications;
    private UserService userService;
    private RegistrationServiceTest.MutableClock clock;
    private PhoneCodes codes;
    private PasswordResetService service;

    /** The code the last send delivered, or null when nothing was sent. */
    private final AtomicReference<String> sentCode = new AtomicReference<>();
    /** The newest row per purpose, served back as the latest — numbers are not told apart. */
    private final Map<VerificationPurpose, PhoneVerification> stored = new EnumMap<>(VerificationPurpose.class);

    @BeforeEach
    void setUp() {
        verifications = mock(PhoneVerificationRepository.class);
        userService = mock(UserService.class);
        clock = new RegistrationServiceTest.MutableClock(START);

        when(verifications.save(any())).thenAnswer(invocation -> {
            PhoneVerification row = invocation.getArgument(0);
            stored.put(row.getPurpose(), row);
            return row;
        });
        when(verifications.findLatestForUpdate(anyString(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.<VerificationPurpose>getArgument(1))));

        User account = mock(User.class);
        when(account.getId()).thenReturn(42L);
        when(account.canSignIn()).thenReturn(true);
        when(userService.findForAuthentication(PHONE)).thenReturn(Optional.of(account));
        when(userService.findForAuthentication(STRANGER)).thenReturn(Optional.empty());

        OtpSender sender = (phone, code, purpose) -> sentCode.set(code);
        codes = new PhoneCodes(verifications, sender,
                new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5),
                clock);
        service = new PasswordResetServiceImpl(codes, userService);
    }

    @Test
    @DisplayName("a number with an account is sent a recovery code")
    void accountGetsACode() {
        service.requestCode("0912 345 678");

        assertNotNull(sentCode.get());
        assertEquals(VerificationPurpose.RESET_PASSWORD, stored.get(VerificationPurpose.RESET_PASSWORD).getPurpose());
    }

    @Test
    @DisplayName("a number without an account is answered the same, and sent nothing")
    void strangerIsAnsweredTheSame() {
        OtpResponse forAccount = service.requestCode(PHONE);
        sentCode.set(null);
        stored.clear();

        OtpResponse forStranger = service.requestCode(STRANGER);

        assertEquals(forAccount, forStranger);
        assertNull(sentCode.get());
        // Recorded anyway, so the cooldown answers a stranger exactly as it answers an account.
        assertNotNull(stored.get(VerificationPurpose.RESET_PASSWORD));
    }

    @Test
    @DisplayName("the right code sets the new password")
    void rightCodeResets() {
        service.requestCode(PHONE);

        service.reset(request(sentCode.get()));

        verify(userService).setPassword(42L, "NewPassw0rd!");
        assertNotNull(stored.get(VerificationPurpose.RESET_PASSWORD).getConsumedAt());
    }

    @Test
    @DisplayName("a wrong code counts an attempt and changes nothing")
    void wrongCodeCountsAnAttempt() {
        service.requestCode(PHONE);
        String code = sentCode.get();
        String wrong = code.substring(0, 5) + (code.charAt(5) == '9' ? '0' : (char) (code.charAt(5) + 1));

        assertThrows(InvalidVerificationCodeException.class, () -> service.reset(request(wrong)));

        assertEquals(1, stored.get(VerificationPurpose.RESET_PASSWORD).getAttempts());
        verify(userService, never()).setPassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("a registration code does not reset a password")
    void registrationCodeIsNotAccepted() {
        codes.issue(PHONE, VerificationPurpose.REGISTER, true);

        assertThrows(InvalidVerificationCodeException.class, () -> service.reset(request(sentCode.get())));
        verify(userService, never()).setPassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("an expired code is refused")
    void expiredCodeIsRefused() {
        service.requestCode(PHONE);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThrows(InvalidVerificationCodeException.class, () -> service.reset(request(sentCode.get())));
        verify(userService, never()).setPassword(anyLong(), anyString());
    }

    private static PasswordResetRequest request(String otp) {
        return new PasswordResetRequest("0912345678", otp, null, "NewPassw0rd!");
    }
}
