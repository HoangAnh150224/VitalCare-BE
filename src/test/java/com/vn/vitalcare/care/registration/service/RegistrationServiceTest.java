package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
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
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        customerService = mock(CustomerService.class);
        authService = mock(AuthService.class);
        service = new RegistrationServiceImpl(userService, customerService, authService);
    }

    @Test
    @DisplayName("creates the account with its email, a neutral customer and a session")
    void registers() {
        User user = mock(User.class);
        when(userService.createSelfRegistered("0912345678", "an@example.com", "Passw0rd!", "Nguyen Van A",
                RegistrationServiceImpl.CUSTOMER_ROLE)).thenReturn(user);

        service.register(request("Passw0rd!"));

        verify(customerService).createNeutral(user);
        verify(authService).signInVerified(user);
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
        return new RegisterRequest("0912345678", "an@example.com", "Nguyen Van A", password);
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
