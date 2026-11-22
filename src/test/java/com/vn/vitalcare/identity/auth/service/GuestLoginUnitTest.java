package com.vn.vitalcare.identity.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.auth.dto.LoginRequest;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.security.JwtService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** UC Log In, sheet Guest_Login: one nested class per unit. */
class GuestLoginUnitTest {

    @Nested
    @DisplayName("LoginRequest (@Valid in AuthController.login)")
    class LoginRequestValidation {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        private Set<String> errors(String phone, String password) {
            return validator.validate(new LoginRequest(phone, password)).stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).collect(Collectors.toSet());
        }

        @Test void utc01_valid() {
            assertEquals(Set.of(), errors("0912345678", "Passw0rd!"));
        }

        @Test void utc02_phoneBlank() {
            assertEquals(Set.of("phone: Phone number is required"), errors("", "Passw0rd!"));
        }

        @Test void utc03_passwordBlank() {
            assertEquals(Set.of("password: Password is required"), errors("0912345678", "   "));
        }

        @Test void utc04_anyPhoneShapeAccepted() {
            assertEquals(Set.of(), errors("abc", "Passw0rd!"));
        }
    }

    @Nested
    @DisplayName("UserService.findForAuthentication")
    class FindForAuthentication {

        private UserRepository repository;
        private UserService service;

        @BeforeEach
        void setUp() {
            repository = mock(UserRepository.class);
            service = new UserService(repository, mock(RoleService.class), mock(RefreshTokenService.class),
                    mock(PasswordEncoder.class), mock(ApplicationEventPublisher.class));
        }

        @Test void utc05_localNumber() {
            User user = mock(User.class);
            when(repository.findByPhone("+84912345678")).thenReturn(Optional.of(user));
            assertEquals(Optional.of(user), service.findForAuthentication("0912345678"));
        }

        @Test void utc06_internationalWithSpaces() {
            service.findForAuthentication("+84 912 345 678");
            verify(repository).findByPhone("+84912345678");
        }

        @Test void utc07_malformed() {
            assertEquals(Optional.empty(), service.findForAuthentication("abc"));
            verify(repository, never()).findByPhone(any());
        }
    }

    @Nested
    @DisplayName("AuthService.login")
    class Login {

        private UserService userService;
        private PasswordEncoder encoder;
        private AuthService auth;
        private User user;

        @BeforeEach
        void setUp() {
            userService = mock(UserService.class);
            encoder = mock(PasswordEncoder.class);
            RefreshTokenService refresh = mock(RefreshTokenService.class);
            JwtService jwt = mock(JwtService.class);
            when(jwt.issueAccessToken(any(), any())).thenReturn("access.jwt");
            when(jwt.accessTokenTtlSeconds()).thenReturn(900L);
            when(refresh.issue(any(), any())).thenReturn("refresh-raw");
            auth = new AuthService(userService, refresh, jwt, encoder,
                    mock(AuthorizationService.class), mock(RowLevelSecurity.class));
            user = mock(User.class);
            when(user.getPasswordHash()).thenReturn("$2a$hash");
            when(user.canSignIn()).thenReturn(true);
            when(user.getFullName()).thenReturn("Nguyen Van A");
            when(userService.findForAuthentication("0912345678")).thenReturn(Optional.of(user));
            when(encoder.matches("Passw0rd!", "$2a$hash")).thenReturn(true);
        }

        @Test void utc08_rightPassword() {
            TokenResponse tokens = auth.login(new LoginRequest("0912345678", "Passw0rd!"));
            assertEquals("access.jwt", tokens.accessToken());
            assertEquals("refresh-raw", tokens.refreshToken());
            assertEquals("Bearer", tokens.tokenType());
            assertEquals(900L, tokens.expiresIn());
            assertEquals("Nguyen Van A", tokens.user().fullName());
            verify(userService).recordLogin(eq(user), any(Instant.class));
        }

        @Test void utc09_wrongPassword() {
            BadCredentialsException e = assertThrows(BadCredentialsException.class,
                    () -> auth.login(new LoginRequest("0912345678", "wrong")));
            assertEquals("Incorrect password", e.getMessage());
            verify(userService, never()).recordLogin(any(), any());
        }

        @Test void utc10_unknownPhoneStillHashes() {
            when(userService.findForAuthentication("0999999999")).thenReturn(Optional.empty());
            BadCredentialsException e = assertThrows(BadCredentialsException.class,
                    () -> auth.login(new LoginRequest("0999999999", "Passw0rd!")));
            assertEquals("No account uses this phone number", e.getMessage());
            // The dummy hash is compared too, so an unknown number takes as long as a known one.
            verify(encoder).matches(eq("Passw0rd!"), anyString());
            verify(userService, never()).recordLogin(any(), any());
        }

        @Test void utc11_inactiveAccount() {
            when(user.canSignIn()).thenReturn(false);
            BadCredentialsException e = assertThrows(BadCredentialsException.class,
                    () -> auth.login(new LoginRequest("0912345678", "Passw0rd!")));
            assertEquals("This account is no longer active", e.getMessage());
            verify(userService, never()).recordLogin(any(), any());
        }

        /** ST-LOGIN: each failure is said for what it is (no account / wrong password), not one shared message. */
        @Test void utc12_eachFailureSaidApart() {
            when(userService.findForAuthentication("0999999999")).thenReturn(Optional.empty());
            String unknown = assertThrows(BadCredentialsException.class,
                    () -> auth.login(new LoginRequest("0999999999", "x"))).getMessage();
            String wrong = assertThrows(BadCredentialsException.class,
                    () -> auth.login(new LoginRequest("0912345678", "x"))).getMessage();
            assertEquals("No account uses this phone number", unknown);
            assertEquals("Incorrect password", wrong);
        }
    }
}
