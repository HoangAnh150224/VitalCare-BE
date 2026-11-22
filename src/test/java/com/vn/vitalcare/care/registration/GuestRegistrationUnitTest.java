package com.vn.vitalcare.care.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.service.AccountRegistered;
import com.vn.vitalcare.care.registration.service.PhoneCodes;
import com.vn.vitalcare.care.registration.service.impl.RegistrationServiceImpl;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.auth.service.AuthService;
import com.vn.vitalcare.identity.auth.service.AuthorizationService;
import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.security.JwtService;
import jakarta.validation.ConstraintViolation;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Guest self-registration (Register Account), one nested class per class in its class diagram.
 * Report-5.1_Unit Test.xlsx, sheet BE_Registration: the test name is in the Note column.
 */
class GuestRegistrationUnitTest {

    static final String OTP = "123456";
    static final String NAME = "Nguyen Van A";

    static RegisterRequest request(String phone, String email, String fullName, String password) {
        return new RegisterRequest(phone, OTP, email, fullName, password);
    }

    @Nested
    @DisplayName("RegisterRequest (@Valid in RegistrationController)")
    class RegisterRequestValidation {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        private Set<String> errors(String phone, String email, String fullName, String password) {
            Set<ConstraintViolation<RegisterRequest>> v =
                    validator.validate(request(phone, email, fullName, password));
            return v.stream().map(x -> x.getPropertyPath() + ": " + x.getMessage()).collect(Collectors.toSet());
        }

        @Test void utc01_valid() { assertTrue(errors("0912345678", null, NAME, "Passw0rd!").isEmpty()); }
        @Test void utc02_plus84() { assertTrue(errors("+84912345678", null, NAME, "Passw0rd!").isEmpty()); }
        @Test void utc07_fullNameBlank() {
            assertTrue(errors("0912345678", null, " ", "Passw0rd!").contains("fullName: Full name is required"));
        }
        @Test void utc08_fullName255() { assertTrue(errors("0912345678", null, "a".repeat(255), "Passw0rd!").isEmpty()); }
        @Test void utc09_fullName256() {
            assertTrue(errors("0912345678", null, "a".repeat(256), "Passw0rd!")
                    .contains("fullName: Full name must be at most 255 characters"));
        }
        @Test void utc10_password7() {
            assertTrue(errors("0912345678", null, NAME, "a".repeat(7))
                    .contains("password: Password must be between 8 and 72 characters"));
        }
        @Test void utc11_password8() { assertTrue(errors("0912345678", null, NAME, "a".repeat(8)).isEmpty()); }
        @Test void utc12_password72() { assertTrue(errors("0912345678", null, NAME, "a".repeat(72)).isEmpty()); }
        @Test void utc13_password73() {
            assertTrue(errors("0912345678", null, NAME, "a".repeat(73))
                    .contains("password: Password must be between 8 and 72 characters"));
        }
        @Test void utc15_emailInvalid() {
            assertTrue(errors("0912345678", "abc", NAME, "Passw0rd!").contains("email: Email must be a valid address"));
        }
    }

    /** The phone's shape is checked by the service (PhoneNumbers.problem), which says which way it is wrong. */
    @Nested
    @DisplayName("RegistrationServiceImpl.register — phone number")
    class PhoneShape {

        private final UserService userService = mock(UserService.class);
        private final PhoneCodes codes = mock(PhoneCodes.class);
        private final RegistrationServiceImpl service = new RegistrationServiceImpl(userService,
                mock(CustomerService.class), mock(AuthService.class), codes, mock(ApplicationEventPublisher.class));

        private String refusal(String phone) {
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.register(request(phone, null, NAME, "Passw0rd!")));
            assertEquals("phone", e.field());
            verifyNoInteractions(userService, codes);
            return e.getMessage();
        }

        @Test void utc04_phoneLetters() { assertEquals(PhoneNumbers.NOT_DIGITS, refusal("abcdef")); }
        @Test void utc05_phone9Digits() { assertEquals(PhoneNumbers.TOO_FEW, refusal("091234567")); }
        @Test void utc06_phone11Digits() { assertEquals(PhoneNumbers.TOO_MANY, refusal("09123456789")); }
    }

    @Nested
    @DisplayName("RegistrationServiceImpl.register")
    class RegistrationServiceRegister {

        private UserService userService;
        private CustomerService customerService;
        private AuthService authService;
        private PhoneCodes codes;
        private ApplicationEventPublisher events;
        private RegistrationServiceImpl service;

        @BeforeEach
        void setUp() {
            userService = mock(UserService.class);
            customerService = mock(CustomerService.class);
            authService = mock(AuthService.class);
            codes = mock(PhoneCodes.class);
            events = mock(ApplicationEventPublisher.class);
            service = new RegistrationServiceImpl(userService, customerService, authService, codes, events);
        }

        private RegisterRequest req(String password) {
            return request("0912345678", null, NAME, password);
        }

        @Test void utc16_returnsTokens() {
            User user = mock(User.class);
            TokenResponse tokens = mock(TokenResponse.class);
            when(userService.createSelfRegistered("+84912345678", null, "Passw0rd!", NAME, "GUEST")).thenReturn(user);
            when(authService.signInVerified(user)).thenReturn(tokens);

            assertSame(tokens, service.register(req("Passw0rd!")));

            verify(codes).verify("+84912345678", VerificationPurpose.REGISTER, OTP);
            verify(customerService).registerAccountProfile(user, null, null);
            verify(events).publishEvent(new AccountRegistered("+84912345678"));
        }

        @Test void utc17_phoneTaken() {
            when(userService.createSelfRegistered(any(), any(), any(), any(), any()))
                    .thenThrow(new ConflictException("That phone number is already in use"));
            ConflictException e = assertThrows(ConflictException.class, () -> service.register(req("Passw0rd!")));
            assertEquals("That phone number is already in use", e.getMessage());
            verifyNoInteractions(customerService, authService, events);
        }

        @Test void utc18_password72BytesAccepted() {
            service.register(req("ệ".repeat(24)));
            verify(userService).createSelfRegistered(any(), any(), eq("ệ".repeat(24)), any(), any());
        }

        @Test void utc19_password75BytesRefused() {
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.register(req("ệ".repeat(25))));
            assertEquals("password", e.field());
            verifyNoInteractions(userService, customerService, authService, codes);
        }
    }

    @Nested
    @DisplayName("UserService.createSelfRegistered")
    class UserServiceCreateSelfRegistered {

        private UserRepository repository;
        private PasswordEncoder encoder;
        private Role guestRole;
        private UserService service;

        @BeforeEach
        void setUp() {
            repository = mock(UserRepository.class);
            when(repository.findByPhone(any())).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
            encoder = mock(PasswordEncoder.class);
            when(encoder.encode("Passw0rd!")).thenReturn("$2a$hash");
            RoleService roles = mock(RoleService.class);
            guestRole = mock(Role.class);
            when(roles.getByCode("GUEST")).thenReturn(guestRole);
            service = new UserService(repository, roles, mock(RefreshTokenService.class), encoder,
                    mock(ApplicationEventPublisher.class));
        }

        @Test void utc20_createsActiveGuest() {
            User u = service.createSelfRegistered("0912345678", null, "Passw0rd!", "  Nguyen Van A ", "GUEST");
            assertEquals("+84912345678", u.getPhone());
            assertEquals("$2a$hash", u.getPasswordHash());
            assertEquals("Nguyen Van A", u.getFullName());
            assertEquals(UserStatus.ACTIVE, u.getStatus());
            assertTrue(u.getRoles().contains(guestRole));
            verify(repository).save(u);
        }

        @Test void utc21_phoneTaken() {
            User other = mock(User.class);
            when(other.getId()).thenReturn(7L);
            when(repository.findByPhone("+84912345678")).thenReturn(Optional.of(other));
            ConflictException e = assertThrows(ConflictException.class,
                    () -> service.createSelfRegistered("0912345678", null, "Passw0rd!", "A", "GUEST"));
            assertEquals("Số điện thoại đã được sử dụng. Xin vui lòng nhập một số khác.", e.getMessage());
            verify(repository, never()).save(any());
        }

        @Test void utc22_samePhoneOtherSpelling() {
            User other = mock(User.class);
            when(other.getId()).thenReturn(7L);
            when(repository.findByPhone("+84912345678")).thenReturn(Optional.of(other));
            assertThrows(ConflictException.class,
                    () -> service.createSelfRegistered("+84 912 345 678", null, "Passw0rd!", "A", "GUEST"));
        }

        @Test void utc23_invalidPhone() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createSelfRegistered("abc", null, "Passw0rd!", "A", "GUEST"));
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("AuthService.signInVerified")
    class AuthServiceSignInVerified {

        @Test void utc24_issuesTokensAndRecordsLogin() {
            UserService userService = mock(UserService.class);
            RefreshTokenService refresh = mock(RefreshTokenService.class);
            JwtService jwt = mock(JwtService.class);
            when(jwt.issueAccessToken(any(), any())).thenReturn("access.jwt");
            when(jwt.accessTokenTtlSeconds()).thenReturn(900L);
            when(refresh.issue(any(), any())).thenReturn("refresh-raw");
            AuthService auth = new AuthService(userService, refresh, jwt, mock(PasswordEncoder.class),
                    mock(AuthorizationService.class), mock(RowLevelSecurity.class));
            User user = mock(User.class);
            when(user.getFullName()).thenReturn("Nguyen Van A");

            TokenResponse t = auth.signInVerified(user);

            assertEquals("access.jwt", t.accessToken());
            assertEquals("refresh-raw", t.refreshToken());
            assertEquals("Bearer", t.tokenType());
            assertEquals(900L, t.expiresIn());
            assertNotNull(t.user());
            verify(userService).recordLogin(eq(user), any(Instant.class));
        }
    }
}
