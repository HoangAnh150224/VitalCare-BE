package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.vn.vitalcare.share.exception.ConflictException;
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
 * The guarantees password recovery rests on: only whoever reads the code
 * emailed to the account can set a new password, the code cannot be walked,
 * and the form says nothing about which numbers have accounts.
 */
class PasswordResetServiceTest {

    private static final String PHONE = "+84912345678";
    private static final String NO_EMAIL = "+84911111111";
    private static final String STRANGER = "+84987654321";
    private static final String EMAIL = "an@example.com";
    private static final Instant START = Instant.parse("2026-10-21T03:00:00Z");

    private PhoneVerificationRepository verifications;
    private UserService userService;
    private RegistrationServiceTest.MutableClock clock;
    private PhoneCodes codes;
    private PasswordResetService service;

    /** The code the last send delivered, or null when nothing was sent. */
    private final AtomicReference<String> sentCode = new AtomicReference<>();
    /** Where the last send went. */
    private final AtomicReference<String> sentTo = new AtomicReference<>();
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

        // Built before stubbing: a mock made inside when(...) leaves Mockito mid-stub.
        User withEmail = account(42L, EMAIL);
        User withoutEmail = account(43L, null);
        when(userService.findForAuthentication(PHONE)).thenReturn(Optional.of(withEmail));
        when(userService.findForAuthentication(NO_EMAIL)).thenReturn(Optional.of(withoutEmail));
        when(userService.findForAuthentication(STRANGER)).thenReturn(Optional.empty());

        OtpSender sender = (email, code, purpose) -> {
            sentTo.set(email);
            sentCode.set(code);
        };
        codes = new PhoneCodes(verifications, sender,
                new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5),
                clock);
        service = new PasswordResetServiceImpl(codes, userService);
    }

    private static User account(long id, String email) {
        User account = mock(User.class);
        when(account.getId()).thenReturn(id);
        when(account.getEmail()).thenReturn(email);
        when(account.canSignIn()).thenReturn(true);
        return account;
    }

    @Test
    @DisplayName("an account's code goes to its email, and only the code's hash is stored")
    void accountGetsACode() {
        service.requestCode("0912 345 678");

        String code = sentCode.get();
        assertEquals(EMAIL, sentTo.get());
        assertTrue(code.matches("\\d{6}"), "code: " + code);
        PhoneVerification row = stored.get(VerificationPurpose.RESET_PASSWORD);
        assertEquals(PHONE, row.getPhone());
        assertFalse(row.getCodeHash().contains(code));
    }

    @Test
    @DisplayName("no account, or an account without email: answered the same, sent nothing")
    void othersAreAnsweredTheSame() {
        OtpResponse forAccount = service.requestCode(PHONE);
        for (String phone : new String[] {STRANGER, NO_EMAIL}) {
            sentCode.set(null);
            stored.clear();

            assertEquals(forAccount, service.requestCode(phone));
            assertNull(sentCode.get());
            // Recorded anyway, so the cooldown answers them exactly as it answers an account.
            assertNotNull(stored.get(VerificationPurpose.RESET_PASSWORD));
        }
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
        when(verifications.countByPhoneAndPurposeAndCreatedAtAfter(eq(PHONE), eq(VerificationPurpose.RESET_PASSWORD),
                any())).thenReturn(5L);

        assertThrows(ConflictException.class, () -> service.requestCode(PHONE));
    }

    @Test
    @DisplayName("the right code sets the new password, once")
    void rightCodeResets() {
        service.requestCode(PHONE);
        String code = sentCode.get();

        service.reset(request(code));

        verify(userService).setPassword(42L, "NewPassw0rd!");
        assertNotNull(stored.get(VerificationPurpose.RESET_PASSWORD).getConsumedAt());
        assertThrows(InvalidVerificationCodeException.class, () -> service.reset(request(code)));
    }

    @Test
    @DisplayName("a wrong code counts an attempt and changes nothing")
    void wrongCodeCountsAnAttempt() {
        service.requestCode(PHONE);

        assertThrows(InvalidVerificationCodeException.class, () -> service.reset(request(wrong(sentCode.get()))));

        assertEquals(1, stored.get(VerificationPurpose.RESET_PASSWORD).getAttempts());
        verify(userService, never()).setPassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("after the last allowed wrong guess, even the right code is refused")
    void codeIsBurnedAfterMaxAttempts() {
        service.requestCode(PHONE);
        String code = sentCode.get();

        for (int i = 0; i < 5; i++) {
            assertThrows(InvalidVerificationCodeException.class, () -> service.reset(request(wrong(code))));
        }
        assertThrows(InvalidVerificationCodeException.class, () -> service.reset(request(code)));
        verify(userService, never()).setPassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("a code issued for another purpose does not reset a password")
    void otherPurposeIsNotAccepted() {
        codes.issue(PHONE, VerificationPurpose.REGISTER, EMAIL);

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
        return new PasswordResetRequest("0912345678", otp, "NewPassw0rd!");
    }

    /** A code guaranteed to differ from {@code code} in its last digit. */
    private static String wrong(String code) {
        char last = code.charAt(code.length() - 1);
        return code.substring(0, code.length() - 1) + (last == '9' ? '0' : (char) (last + 1));
    }
}
