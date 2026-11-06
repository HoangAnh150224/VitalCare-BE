package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.service.impl.RegistrationServiceImpl;
import com.vn.vitalcare.identity.auth.service.AuthService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Self-registration: the account, its customer record and a session, all or nothing. */
class RegistrationServiceTest {

    private UserService userService;
    private CustomerService customerService;
    private AuthService authService;
    private PhoneCodes codes;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        customerService = mock(CustomerService.class);
        authService = mock(AuthService.class);
        codes = mock(PhoneCodes.class);
        service = new RegistrationServiceImpl(userService, customerService, authService, codes);
    }

    @Test
    @DisplayName("creates a Guest account, its linked patient profile, and a session")
    void registers() {
        User user = mock(User.class);
        when(userService.createSelfRegistered("+84912345678", "an@example.com", "Passw0rd!", "Nguyen Van A",
                RegistrationServiceImpl.GUEST_ROLE)).thenReturn(user);

        service.register(request("Passw0rd!"));

        verify(codes).verify("+84912345678", VerificationPurpose.REGISTER, "123456");
        verify(customerService).registerAccountProfile(user, null);
        verify(authService).signInVerified(user);
    }

    @Test
    @DisplayName("does not create an account when the phone code is invalid")
    void invalidCodeStopsRegistration() {
        doThrow(new InvalidVerificationCodeException())
                .when(codes).verify("+84912345678", VerificationPurpose.REGISTER, "123456");

        assertThrows(InvalidVerificationCodeException.class, () -> service.register(request("Passw0rd!")));
        verifyNoInteractions(userService, customerService, authService);
    }

    @Test
    @DisplayName("does not send a registration code to an all-zero phone number")
    void allZeroPhoneIsRefused() {
        FieldValidationException exception = assertThrows(
                FieldValidationException.class, () -> service.requestCode("0000000000"));

        assertEquals("phone", exception.field());
        verifyNoInteractions(codes);
    }

    @Test
    @DisplayName("does not register an all-zero phone even if an OTP is supplied")
    void registrationWithAllZeroPhoneIsRefused() {
        RegisterRequest request = new RegisterRequest("0000000000", "123456", "an@example.com",
                "Nguyen Van A", "Passw0rd!");

        FieldValidationException exception = assertThrows(FieldValidationException.class,
                () -> service.register(request));

        assertEquals("phone", exception.field());
        verifyNoInteractions(codes, userService, customerService, authService);
    }

    @Test
    @DisplayName("keeps an existing structurally valid seed phone eligible for registration")
    void numberWithNonZeroSignificantDigitsIsAllowed() {
        service.requestCode("+84 000 000 131");

        verify(codes).issue("+84000000131", VerificationPurpose.REGISTER, "+84000000131");
    }

    @Test
    @DisplayName("does not create an account with a one-character name")
    void oneCharacterNameIsRefused() {
        for (String name : java.util.List.of("A", " A ")) {
            RegisterRequest request = new RegisterRequest("0912345678", "123456", "an@example.com",
                    name, "Passw0rd!");

            FieldValidationException exception = assertThrows(FieldValidationException.class,
                    () -> service.register(request));

            assertEquals("fullName", exception.field());
        }
        verifyNoInteractions(codes, userService, customerService, authService);
    }

    @Test
    @DisplayName("a number or email another account holds stops it before anything else")
    void takenIsRefused() {
        when(userService.createSelfRegistered(any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("That email address is already in use"));

        assertThrows(ConflictException.class, () -> service.register(request("Passw0rd!")));
        verifyNoInteractions(customerService, authService);
    }

    @Test
    @DisplayName("a password past BCrypt's 72 bytes is refused, nothing created")
    void tooLongPassword() {
        assertThrows(FieldValidationException.class, () -> service.register(request("ệ".repeat(30))));
        verify(userService, never()).createSelfRegistered(any(), any(), any(), any(), any());
    }

    private static RegisterRequest request(String password) {
        return new RegisterRequest("0912345678", "123456", "an@example.com", "Nguyen Van A", password);
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
