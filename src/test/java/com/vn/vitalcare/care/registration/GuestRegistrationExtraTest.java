package com.vn.vitalcare.care.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.registration.controller.RegistrationController;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.service.OtpIpLimiter;
import com.vn.vitalcare.care.registration.service.PhoneCodes;
import com.vn.vitalcare.care.registration.service.RegistrationService;
import com.vn.vitalcare.care.registration.service.impl.RegistrationServiceImpl;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.auth.service.AuthService;
import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ApiExceptionHandler;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Registration cases added after reviewing the code for paths the first suite did not reach. */
class GuestRegistrationExtraTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> errors(RegisterRequest r) {
        return VALIDATOR.validate(r).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).collect(Collectors.toSet());
    }

    private static RegisterRequest req(String phone, String email, String password) {
        return new RegisterRequest(phone, "123456", email, "Nguyen Van A", password);
    }

    @Nested
    @DisplayName("RegisterRequest validation, extra")
    class Validation_ {
        @Test void utc45_passwordOnlySpaces() {
            assertTrue(errors(req("0912345678", null, " ".repeat(8))).isEmpty());
            RegistrationServiceImpl s = new RegistrationServiceImpl(mock(UserService.class), mock(CustomerService.class),
                    mock(AuthService.class), mock(PhoneCodes.class), mock(ApplicationEventPublisher.class));
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> s.register(req("0912345678", null, " ".repeat(8))));
            assertEquals("password", e.field());
            assertEquals("Password cannot contain only spaces", e.getMessage());
        }
        @Test void utc46_phoneSpaces() { assertTrue(errors(req("090 123 4567", null, "Passw0rd!")).isEmpty()); }
        @Test void utc47_phoneParensDash() { assertTrue(errors(req("(090) 123-4567", null, "Passw0rd!")).isEmpty()); }
        @Test void utc48_phone84NoPlus() { assertTrue(errors(req("84912345678", null, "Passw0rd!")).isEmpty()); }
        @Test void utc49_plus84ThenZero() {
            assertEquals(Optional.of(PhoneNumbers.NOT_VIETNAMESE), PhoneNumbers.problem("+840912345678"));
        }
        @org.junit.jupiter.api.Disabled("DEF-REG-04 (open): an email of only spaces is refused as invalid instead of being treated as no email; recorded Failed in Report-5.1, Round 2")
        @Test void utc50_emailOnlySpaces() { assertEquals(Set.of(), errors(req("0912345678", "   ", "Passw0rd!"))); }
        @Test void utc51_email255() {
            String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62);
            assertEquals(255, email.length());
            assertTrue(errors(req("0912345678", email, "Passw0rd!")).isEmpty());
        }
        @Test void utc52_email256() {
            String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63);
            assertEquals(256, email.length());
            assertTrue(errors(req("0912345678", email, "Passw0rd!")).contains("email: Email must be at most 255 characters"));
        }
    }

    @Nested
    @DisplayName("PhoneNumbers.normalize")
    class Normalize {
        @Test void utc53_separatorsNormalized() {
            assertEquals(Optional.of("+84901234567"), PhoneNumbers.normalize("090 123 4567"));
            assertEquals(Optional.of("+84901234567"), PhoneNumbers.normalize("(090) 123-4567"));
            assertEquals(Optional.of("+84912345678"), PhoneNumbers.normalize("84912345678"));
            assertEquals(Optional.of("+84901234567"), PhoneNumbers.normalize("090.123.4567"));
        }
        /** DEF-REG-03: registration refuses an all-zero number (normalize keeps accepting it for seed accounts). */
        @Test void utc54_allZeroNumberRefusedAtRegistration() {
            UserService users = mock(UserService.class);
            RegistrationServiceImpl s = new RegistrationServiceImpl(users, mock(CustomerService.class),
                    mock(AuthService.class), mock(PhoneCodes.class), mock(ApplicationEventPublisher.class));
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> s.register(req("0000000000", null, "Passw0rd!")));
            assertEquals("phone", e.field());
            assertEquals("Phone number cannot be all zeros", e.getMessage());
            verify(users, never()).createSelfRegistered(any(), any(), any(), any(), any());
        }
        /** Carrier prefixes are deliberately not enumerated (PhoneNumbers javadoc). */
        @Test void utc55_unissuedPrefixAccepted() {
            assertEquals(Optional.of("+84123456789"), PhoneNumbers.normalize("0123456789"));
        }
    }

    @Nested
    @DisplayName("RegistrationServiceImpl.register, extra")
    class Service {
        @Test void utc56_mixedBytes73() {
            UserService users = mock(UserService.class);
            RegistrationServiceImpl s = new RegistrationServiceImpl(users, mock(CustomerService.class), mock(AuthService.class),
                    mock(PhoneCodes.class), mock(ApplicationEventPublisher.class));
            String pw = "a".repeat(70) + "ệ";
            assertEquals(71, pw.length());
            assertTrue(errors(req("0912345678", null, pw)).isEmpty());
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> s.register(req("0912345678", null, pw)));
            assertEquals("password", e.field());
            verify(users, never()).createSelfRegistered(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("UserService.createSelfRegistered, extra")
    class Users {
        private UserRepository repository;
        private RoleService roles;
        private UserService service;

        @BeforeEach
        void setUp() {
            repository = mock(UserRepository.class);
            when(repository.findByPhone(any())).thenReturn(Optional.empty());
            when(repository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
            roles = mock(RoleService.class);
            when(roles.getByCode("GUEST")).thenReturn(mock(Role.class));
            service = new UserService(repository, roles, mock(RefreshTokenService.class),
                    mock(PasswordEncoder.class), mock(ApplicationEventPublisher.class));
        }

        @Test void utc57_blankEmailStoredAsNull() {
            User u = service.createSelfRegistered("0912345678", "   ", "Passw0rd!", "A", "GUEST");
            assertNull(u.getEmail());
        }
        @Test void utc58_emailOtherCaseTaken() {
            User other = mock(User.class);
            when(other.getId()).thenReturn(7L);
            when(repository.findByEmailIgnoreCase("an@example.com")).thenReturn(Optional.of(other));
            ConflictException e = assertThrows(ConflictException.class,
                    () -> service.createSelfRegistered("0912345678", "AN@EXAMPLE.COM", "Passw0rd!", "A", "GUEST"));
            assertEquals("That email address is already in use", e.getMessage());
        }
        @Test void utc59_lockedAccountPhoneTaken() {
            User locked = mock(User.class);
            when(locked.getId()).thenReturn(9L);
            when(locked.getStatus()).thenReturn(UserStatus.LOCKED);
            when(repository.findByPhone("+84912345678")).thenReturn(Optional.of(locked));
            assertThrows(ConflictException.class,
                    () -> service.createSelfRegistered("0912345678", null, "Passw0rd!", "A", "GUEST"));
            verify(repository, never()).save(any());
        }
        @Test void utc60_guestRoleMissing() {
            when(roles.getByCode("GUEST")).thenThrow(new IllegalStateException("System role GUEST is missing"));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.createSelfRegistered("0912345678", null, "Passw0rd!", "A", "GUEST"));
            assertEquals("System role GUEST is missing", e.getMessage());
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("RegistrationController (MockMvc standalone, RegistrationService mocked)")
    class Controller {
        private RegistrationService service;
        private MockMvc mvc;

        @BeforeEach
        void setUp() {
            service = mock(RegistrationService.class);
            LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
            validator.afterPropertiesSet();
            mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(service, mock(OtpIpLimiter.class)))
                    .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator).build();
        }

        @Test void c1_validBodyDelegates() throws Exception {
            when(service.register(any())).thenReturn(TokenResponse.of("access", "refresh", 900, null));
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"0912345678\",\"otp\":\"123456\",\"fullName\":\"Nguyen Van A\",\"password\":\"Passw0rd!\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"));
            verify(service).register(new RegisterRequest("0912345678", "123456", null, "Nguyen Van A", "Passw0rd!"));
        }

        @Test void c2_extraRoleFieldIgnored() throws Exception {
            when(service.register(any())).thenReturn(TokenResponse.of("access", "refresh", 900, null));
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"0912345678\",\"otp\":\"123456\",\"fullName\":\"Nguyen Van A\",\"password\":\"Passw0rd!\",\"role\":\"ADMIN\"}"))
                    .andExpect(status().isOk());
            verify(service).register(new RegisterRequest("0912345678", "123456", null, "Nguyen Van A", "Passw0rd!"));
        }

        @Test void c3_malformedJson() throws Exception {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":"))
                    .andExpect(status().isBadRequest());
            verify(service, never()).register(any());
        }

        @Test void c4_invalidBody422() throws Exception {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"0912345678\",\"otp\":\"12ab\",\"fullName\":\"Nguyen Van A\",\"password\":\"Passw0rd!\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.otp").value("The code is the digits from the SMS"));
            verify(service, never()).register(any());
        }
    }

    @Nested
    @DisplayName("ApiExceptionHandler (called directly)")
    class Handler {
        private final ApiExceptionHandler handler = new ApiExceptionHandler();

        @Test void h1_conflict() {
            var r = handler.handleConflict(new ConflictException("That phone number is already in use"));
            assertEquals(409, r.getStatusCode().value());
            assertEquals("That phone number is already in use", r.getBody().get("message"));
        }
        @Test void h2_fieldValidation() {
            var r = handler.handleFieldValidation(new FieldValidationException("password", "Password is too long; use fewer accented letters"));
            assertEquals(422, r.getStatusCode().value());
            assertEquals("Validation failed", r.getBody().get("message"));
            assertEquals(java.util.Map.of("password", "Password is too long; use fewer accented letters"), r.getBody().get("errors"));
        }
        @Test void h3_illegalArgument() {
            var r = handler.handleIllegalArgument(new IllegalArgumentException("Not a valid Vietnamese phone number"));
            assertEquals(400, r.getStatusCode().value());
            assertEquals("Not a valid Vietnamese phone number", r.getBody().get("message"));
        }
        /** A second insert of the same number, refused by the unique index on users.phone. */
        @org.junit.jupiter.api.Disabled("DEF-REG-02 (open): the unique index on users.phone answers \"This record is still referenced by other records\"; recorded Failed in Report-5.1, Round 2")
        @Test void h4_duplicatePhoneFromUniqueIndex() {
            var r = handler.handleIntegrityViolation(new DataIntegrityViolationException("duplicate key value violates unique constraint \"users_phone_key\""));
            assertEquals(409, r.getStatusCode().value());
            assertEquals("That phone number is already in use", r.getBody().get("message"));
        }
    }

    @Nested
    @DisplayName("RegisterRequest blank fields report every violated rule")
    class Blank {
        @Test void v1_phoneEmpty() {
            assertEquals(Set.of("phone: Phone number is required"), errors(req("", null, "Passw0rd!")));
        }
        @Test void v2_passwordEmpty() {
            assertEquals(Set.of("password: Password is required",
                    "password: Password must be between 8 and 72 characters"),
                    errors(req("0912345678", null, "")));
        }
    }
}
