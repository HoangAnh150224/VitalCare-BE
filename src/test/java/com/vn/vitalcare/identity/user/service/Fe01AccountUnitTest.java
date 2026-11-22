package com.vn.vitalcare.identity.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.customer.dto.ProfileRequest;
import com.vn.vitalcare.care.customer.repository.CustomerRepository;
import com.vn.vitalcare.care.customer.service.impl.CustomerServiceImpl;
import com.vn.vitalcare.care.registration.dto.OtpResponse;
import com.vn.vitalcare.care.registration.dto.PasswordResetRequest;
import com.vn.vitalcare.care.registration.entity.PhoneVerification;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.repository.PhoneVerificationRepository;
import com.vn.vitalcare.care.registration.service.InvalidVerificationCodeException;
import com.vn.vitalcare.care.registration.service.OtpProperties;
import com.vn.vitalcare.care.registration.service.OtpSender;
import com.vn.vitalcare.care.registration.service.PhoneCodes;
import com.vn.vitalcare.care.registration.service.impl.PasswordResetServiceImpl;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.identity.auth.dto.ChangePasswordRequest;
import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.dto.RoleRef;
import com.vn.vitalcare.identity.user.dto.UserPatchRequest;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FE-01 Authentication & RBAC — Forgot/Reset/Change Password, User Profile, user administration. */
class Fe01AccountUnitTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violations(Object dto) {
        return VALIDATOR.validate(dto).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ Forgot / Reset Password
    @Nested
    @DisplayName("PhoneCodes (one-time codes by SMS)")
    class Codes {
        private final Instant now = Instant.parse("2026-11-01T03:00:00Z");
        private final OtpProperties props = new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5);
        private PhoneVerificationRepository repo;
        private OtpSender sender;
        private PhoneCodes codes;

        @BeforeEach
        void setUp() {
            repo = mock(PhoneVerificationRepository.class);
            sender = mock(OtpSender.class);
            codes = new PhoneCodes(repo, sender, props, Clock.fixed(now, ZoneOffset.UTC));
            when(repo.findLatestForUpdate(anyString(), any())).thenReturn(Optional.empty());
        }

        @Test void fp01_issueSendsSixDigitsAndStoresOnlyTheHash() {
            OtpResponse r = codes.issue("+84912345678", VerificationPurpose.RESET_PASSWORD, "+84912345678");
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(sender).send(eq("+84912345678"), code.capture(), eq(VerificationPurpose.RESET_PASSWORD));
            assertTrue(code.getValue().matches("\\d{6}"));
            ArgumentCaptor<PhoneVerification> saved = ArgumentCaptor.forClass(PhoneVerification.class);
            verify(repo).save(saved.capture());
            assertNotEquals(code.getValue(), saved.getValue().getCodeHash());
            assertEquals(now.plus(Duration.ofMinutes(5)), saved.getValue().getExpiresAt());
            assertEquals(300, r.expiresIn());
            assertEquals(60, r.resendAfter());
        }

        @Test void fp02_numberWithoutAccountGetsSameAnswerButNoSms() {
            OtpResponse r = codes.issue("+84999999999", VerificationPurpose.RESET_PASSWORD, null);
            verify(sender, never()).send(any(), any(), any());
            verify(repo).save(any());
            assertEquals(300, r.expiresIn());
        }

        @Test void fp03_resendInsideCooldownRefused() {
            PhoneVerification last = new PhoneVerification("+84912345678", VerificationPurpose.RESET_PASSWORD, "h",
                    now.minusSeconds(20), now.plusSeconds(280));
            when(repo.findLatestForUpdate(anyString(), any())).thenReturn(Optional.of(last));
            ConflictException e = assertThrows(ConflictException.class,
                    () -> codes.issue("+84912345678", VerificationPurpose.RESET_PASSWORD, "+84912345678"));
            assertEquals("Please wait 40 seconds before asking for another code", e.getMessage());
            verify(sender, never()).send(any(), any(), any());
        }

        @Test void fp04_sixthCodeInAnHourRefused() {
            when(repo.countByPhoneAndPurposeAndCreatedAtAfter(anyString(), any(), any())).thenReturn(5L);
            ConflictException e = assertThrows(ConflictException.class,
                    () -> codes.issue("+84912345678", VerificationPurpose.RESET_PASSWORD, "+84912345678"));
            assertEquals("Too many codes have been sent to this number; it is locked for 24 hours", e.getMessage());
        }

        private String issueAndCapture() {
            codes.issue("+84912345678", VerificationPurpose.RESET_PASSWORD, "+84912345678");
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(sender).send(any(), code.capture(), any());
            ArgumentCaptor<PhoneVerification> saved = ArgumentCaptor.forClass(PhoneVerification.class);
            verify(repo).save(saved.capture());
            when(repo.findLatestForUpdate(anyString(), any())).thenReturn(Optional.of(saved.getValue()));
            return code.getValue();
        }

        @Test void fp05_rightCodeIsSpent() {
            String code = issueAndCapture();
            codes.verify("+84912345678", VerificationPurpose.RESET_PASSWORD, code);
            PhoneVerification v = repo.findLatestForUpdate("+84912345678", VerificationPurpose.RESET_PASSWORD).get();
            assertEquals(now, v.getConsumedAt());
            assertThrows(InvalidVerificationCodeException.class,
                    () -> codes.verify("+84912345678", VerificationPurpose.RESET_PASSWORD, code));
        }

        @Test void fp06_wrongCodeCountedAndRefused() {
            String code = issueAndCapture();
            String wrong = code.equals("000000") ? "111111" : "000000";
            assertThrows(InvalidVerificationCodeException.class,
                    () -> codes.check("+84912345678", VerificationPurpose.RESET_PASSWORD, wrong));
            assertEquals(1, repo.findLatestForUpdate("+84912345678", VerificationPurpose.RESET_PASSWORD).get().getAttempts());
        }

        @Test void fp07_fiveWrongGuessesBurnTheCode() {
            String code = issueAndCapture();
            String wrong = code.equals("000000") ? "111111" : "000000";
            for (int i = 0; i < 5; i++) {
                assertThrows(InvalidVerificationCodeException.class,
                        () -> codes.check("+84912345678", VerificationPurpose.RESET_PASSWORD, wrong));
            }
            assertThrows(InvalidVerificationCodeException.class,
                    () -> codes.check("+84912345678", VerificationPurpose.RESET_PASSWORD, code));
        }

        @Test void fp08_expiredCodeRefused() {
            PhoneVerification old = new PhoneVerification("+84912345678", VerificationPurpose.RESET_PASSWORD, "h",
                    now.minusSeconds(400), now.minusSeconds(100));
            when(repo.findLatestForUpdate(anyString(), any())).thenReturn(Optional.of(old));
            assertThrows(InvalidVerificationCodeException.class,
                    () -> codes.check("+84912345678", VerificationPurpose.RESET_PASSWORD, "123456"));
        }
    }

    @Nested
    @DisplayName("PasswordResetServiceImpl")
    class Reset {
        private PhoneCodes codes;
        private UserService users;
        private PasswordResetServiceImpl service;
        private User active;

        @BeforeEach
        void setUp() {
            codes = mock(PhoneCodes.class);
            users = mock(UserService.class);
            service = new PasswordResetServiceImpl(codes, users);
            active = mock(User.class);
            when(active.canSignIn()).thenReturn(true);
            when(active.getId()).thenReturn(7L);
            when(users.findForAuthentication("+84912345678")).thenReturn(Optional.of(active));
            when(users.findForAuthentication("+84999999999")).thenReturn(Optional.empty());
        }

        @Test void fp09_requestNormalisesAndSendsOnlyToAnAccount() {
            service.requestCode("0912 345 678");
            verify(codes).issue("+84912345678", VerificationPurpose.RESET_PASSWORD, "+84912345678");
            service.requestCode("0999999999");
            verify(codes).issue(eq("+84999999999"), eq(VerificationPurpose.RESET_PASSWORD), isNull());
        }

        @Test void fp10_malformedNumberRefused() {
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> service.requestCode("abc"));
            assertEquals("phone", e.field());
        }

        @Test void rp01_rightCodeSetsTheNewPassword() {
            service.reset(new PasswordResetRequest("0912345678", "123456", "NewPassw0rd!"));
            verify(codes).verify("+84912345678", VerificationPurpose.RESET_PASSWORD, "123456");
            verify(users).setPassword(7L, "NewPassw0rd!");
        }

        @Test void rp02_passwordOver72BytesRefusedBeforeTheCodeIsSpent() {
            FieldValidationException e = assertThrows(FieldValidationException.class,
                    () -> service.reset(new PasswordResetRequest("0912345678", "123456", "ệ".repeat(25))));
            assertEquals("newPassword", e.field());
            verify(codes, never()).verify(any(), any(), any());
        }

        @Test void rp03_disabledAccountCannotReset() {
            when(active.canSignIn()).thenReturn(false);
            assertThrows(InvalidVerificationCodeException.class,
                    () -> service.reset(new PasswordResetRequest("0912345678", "123456", "NewPassw0rd!")));
            verify(users, never()).setPassword(any(), any());
        }

        @Test void rp04_resetRequestValidation() {
            assertTrue(violations(new PasswordResetRequest("0912345678", "12ab", "NewPassw0rd!"))
                    .contains("otp: The code is the digits from the SMS"));
            assertTrue(violations(new PasswordResetRequest("0912345678", "123456", "short"))
                    .contains("newPassword: Password must be between 8 and 72 characters"));
        }
    }

    // ------------------------------------------------------------------ Change Password + user administration
    @Nested
    @DisplayName("UserService")
    class Users {
        private UserRepository repo;
        private RoleService roles;
        private RefreshTokenService tokens;
        private PasswordEncoder encoder;
        private ApplicationEventPublisher events;
        private UserService service;
        private final Role admin = new Role("ADMIN", "System Administrator", "", true);
        private final Role doctor = new Role("DOCTOR", "Doctor", "", true);

        @BeforeEach
        void setUp() {
            repo = mock(UserRepository.class);
            roles = mock(RoleService.class);
            tokens = mock(RefreshTokenService.class);
            encoder = mock(PasswordEncoder.class);
            events = mock(ApplicationEventPublisher.class);
            service = new UserService(repo, roles, tokens, encoder, events);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
            when(encoder.encode(anyString())).thenAnswer(i -> "$2a$" + i.getArgument(0));
        }

        private User user(UserStatus status, Role... rs) {
            User u = new User("+84912345678", null, "$2a$old", "Nguyen Van A", status);
            u.setRoles(new LinkedHashSet<>(List.of(rs)));
            // A saved account has an id; the event that announces new authorities carries it.
            org.springframework.test.util.ReflectionTestUtils.setField(u, "id", 5L);
            when(repo.findById(5L)).thenReturn(Optional.of(u));
            return u;
        }

        @Test void cp01_rightCurrentPasswordChangesItAndEndsOtherSessions() {
            User u = user(UserStatus.ACTIVE, doctor);
            when(encoder.matches("OldPassw0rd!", "$2a$old")).thenReturn(true);
            service.changeOwnPassword(5L, "OldPassw0rd!", "NewPassw0rd!");
            assertEquals("$2a$NewPassw0rd!", u.getPasswordHash());
            verify(tokens).revokeAllFor(eq(u), any());
        }

        @Test void cp02_wrongCurrentPasswordRefused() {
            User u = user(UserStatus.ACTIVE, doctor);
            ConflictException e = assertThrows(ConflictException.class,
                    () -> service.changeOwnPassword(5L, "wrong", "NewPassw0rd!"));
            assertEquals("The current password is not correct", e.getMessage());
            assertEquals("$2a$old", u.getPasswordHash());
            verify(tokens, never()).revokeAllFor(any(), any());
        }

        @Test void cp03_changePasswordRequestValidation() {
            assertTrue(violations(new ChangePasswordRequest("", "NewPassw0rd!")).contains("currentPassword: Current password is required"));
            assertTrue(violations(new ChangePasswordRequest("OldPassw0rd!", "1234567"))
                    .contains("newPassword: New password must be between 8 and 72 characters"));
            // Same 72 limit as registration and reset now (BCrypt's limit).
            assertTrue(violations(new ChangePasswordRequest("OldPassw0rd!", "a".repeat(72))).isEmpty());
            assertTrue(violations(new ChangePasswordRequest("OldPassw0rd!", "a".repeat(73)))
                    .contains("newPassword: New password must be between 8 and 72 characters"));
        }

        @Test void um01_unknownUserIs404() {
            when(repo.findById(99L)).thenReturn(Optional.empty());
            assertThrows(ResourceNotFoundException.class, () -> service.get(99L));
        }

        /** Roles come from their workflow (registration, activation, staff creation): never changed by hand. */
        @Test void um02_systemRolesAreNotChangedByHand() {
            User u = user(UserStatus.ACTIVE, doctor);
            Role nurse = new Role("NURSE", "Nurse", "", true);
            when(roles.resolveAll(List.of(3L))).thenReturn(List.of(nurse));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> service.update(5L, new UserPatchRequest(null, null, null, null, List.of(new RoleRef(3L)))));
            assertEquals(Set.of("DOCTOR"), u.roleCodes());
            verify(events, never()).publishEvent(any(AuthoritiesChanged.ForUser.class));
        }

        @Test void um03_renamingDoesNotTouchAuthorities() {
            User u = user(UserStatus.ACTIVE, doctor);
            service.update(5L, new UserPatchRequest(null, null, "  Tran Thi B ", null, null));
            assertEquals("Tran Thi B", u.getFullName());
            verify(events, never()).publishEvent(any());
            verify(tokens, never()).revokeAllFor(any(), any());
        }

        @Test void us01_deactivatingEndsEverySession() {
            User u = user(UserStatus.ACTIVE, doctor);
            service.update(5L, new UserPatchRequest(null, null, null, UserStatus.INACTIVE, null));
            assertEquals(UserStatus.INACTIVE, u.getStatus());
            verify(tokens).revokeAllFor(eq(u), any());
            verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(any(AuthoritiesChanged.ForUser.class));
        }

        @Test void us02_lastActiveAdministratorCannotBeSwitchedOff() {
            User u = user(UserStatus.ACTIVE, admin);
            when(repo.countActiveWithRole("ADMIN")).thenReturn(1L);
            ConflictException e = assertThrows(ConflictException.class,
                    () -> service.update(5L, new UserPatchRequest(null, null, null, UserStatus.LOCKED, null)));
            assertEquals("The single System Administrator account cannot be demoted, disabled or deleted through account management", e.getMessage());
            assertEquals(UserStatus.ACTIVE, u.getStatus());
        }

        @Test void us03_lastAdministratorCannotLoseTheRole() {
            user(UserStatus.ACTIVE, admin);
            when(repo.countActiveWithRole("ADMIN")).thenReturn(1L);
            when(roles.resolveAll(List.of(2L))).thenReturn(List.of(doctor));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> service.update(5L, new UserPatchRequest(null, null, null, null, List.of(new RoleRef(2L)))));
        }

        /** The System Administrator is one provisioned account: never locked through account management. */
        @Test void us04_systemAdministratorNeverLocked() {
            User u = user(UserStatus.ACTIVE, admin);
            when(repo.countActiveWithRole("ADMIN")).thenReturn(2L);
            assertThrows(ConflictException.class,
                    () -> service.update(5L, new UserPatchRequest(null, null, null, UserStatus.LOCKED, null)));
            assertEquals(UserStatus.ACTIVE, u.getStatus());
        }

        @Test void us05_reactivatingDoesNotUnlockALockedAccount() {
            User u = user(UserStatus.LOCKED, doctor);
            service.setSignInAllowed(5L, true);
            assertEquals(UserStatus.LOCKED, u.getStatus());
            User v = user(UserStatus.INACTIVE, doctor);
            service.setSignInAllowed(5L, true);
            assertEquals(UserStatus.ACTIVE, v.getStatus());
        }

        @Test void us06_adminSetPasswordEndsEverySession() {
            User u = user(UserStatus.ACTIVE, doctor);
            service.setPassword(5L, "Temp@12345");
            assertEquals("$2a$Temp@12345", u.getPasswordHash());
            verify(tokens).revokeAllFor(eq(u), any());
        }

        @Test void um04_patchValidation() {
            assertTrue(violations(new UserPatchRequest(null, null, null, null, List.of()))
                    .contains("roles: At least one role is required"));
            assertTrue(violations(new UserPatchRequest(null, "abc", null, null, null))
                    .contains("email: Email must be a valid address"));
        }
    }

    // ------------------------------------------------------------------ User Profile / Update User Profile
    @Nested
    @DisplayName("CustomerServiceImpl own profiles")
    class Profile {
        private CustomerRepository repo;
        private CustomerServiceImpl service;
        private Customer owner;

        @BeforeEach
        void setUp() {
            repo = mock(CustomerRepository.class);
            service = new CustomerServiceImpl(repo, mock(ClinicScope.class), mock(RowLevelSecurity.class), Clock.systemUTC());
            owner = mock(Customer.class);
            when(owner.getId()).thenReturn(10L);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        private ProfileRequest req(String name, String phone, String relationship, LocalDate dob, String nationalId) {
            return new ProfileRequest(name, phone, dob, "female", relationship, nationalId, "cccd", null,
                    null, null, null, "Hà Nội", null, "1 Phố Huế");
        }

        @Test void pf01_ownProfileIsTheOwner() {
            assertSame(owner, service.ownProfile(owner, 10L));
        }

        @Test void pf02_someoneElsesProfileIs404() {
            when(repo.findByIdAndGuardianIdAndDeletedAtIsNull(99L, 10L)).thenReturn(Optional.empty());
            assertThrows(ResourceNotFoundException.class, () -> service.ownProfile(owner, 99L));
        }

        @Test void pf03_updateOwnProfileSavesIdentityFields() {
            Customer self = new Customer();
            when(repo.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(self));
            Customer saved = service.updateOwnProfile(owner, 10L,
                    req(null, null, null, LocalDate.of(1991, 3, 14), "  001191004521 "));
            assertEquals(LocalDate.of(1991, 3, 14), saved.getDateOfBirth());
            assertEquals("female", saved.getGender());
            assertEquals("001191004521", saved.getNationalId());
            assertEquals("cccd", saved.getNationalIdType());
        }

        @Test void pf04_relativeNeedsAName() {
            Customer dependent = new Customer();
            dependent.setGuardian(owner);
            when(repo.findByIdAndGuardianIdAndDeletedAtIsNull(11L, 10L)).thenReturn(Optional.of(dependent));
            when(repo.findByIdAndDeletedAtIsNull(11L)).thenReturn(Optional.of(dependent));
            FieldValidationException e = assertThrows(FieldValidationException.class, () -> service.updateOwnProfile(owner, 11L,
                    req("  ", "0912345678", "child", LocalDate.of(2018, 9, 21), "001218009873")));
            assertEquals("fullName", e.field());
        }

        @Test void pf05_profileRequestValidation() {
            Set<String> v = violations(new ProfileRequest(null, null, LocalDate.now().plusDays(1), "x", null, " ",
                    null, null, null, null, null, null, null, null));
            assertTrue(v.contains("dateOfBirth: Date of birth must be in the past"));
            assertTrue(v.contains("gender: Gender must be male, female or other"));
            assertTrue(v.contains("nationalId: A citizen id or personal identification number is required"));
            assertNotNull(v);
        }
    }
}
