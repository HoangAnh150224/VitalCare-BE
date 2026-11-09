package com.vn.vitalcare.identity.user.service;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.dto.RoleRef;
import com.vn.vitalcare.identity.user.dto.UserPatchRequest;
import com.vn.vitalcare.identity.user.dto.UserRequest;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.identity.user.repository.UserSpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    /**
     * The one System Administrator account provisioned for the deployment.
     *
     * <p>It cannot be created, reassigned, disabled, demoted or deleted through
     * ordinary account and role management. The database also enforces that no
     * second account can hold this role.
     */
    private static final String ADMIN_ROLE_CODE = "ADMIN";
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final String GUEST_ROLE_CODE = "GUEST";
    private static final String PATIENT_ROLE_CODE = "PATIENT";
    private static final Set<String> WORKFLOW_ROLE_CODES = Set.of(
            ADMIN_ROLE_CODE, "CLINIC_ADMIN", "DOCTOR", "MANAGER", "NURSE", "MEDICAL_STAFF", "USER",
            "CARE_GIVER", "CAREGIVER", GUEST_ROLE_CODE, PATIENT_ROLE_CODE);

    /** Sort properties the list endpoint accepts; anything else is ignored. */
    private static final Set<String> SORTABLE =
            Set.of("id", "phone", "email", "fullName", "status", "createdAt", "lastLoginAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final UserRepository repository;

    // Roles are resolved through the role domain's own service rather than its
    // repository, which also means an unknown role id produces the same 404 the
    // role endpoints would.
    private final RoleService roleService;

    // Disabling an account, resetting its password or moving its sign-in
    // number has to end the sessions
    // already open under it, or the change would not take effect until the
    // refresh token expired days later.
    private final RefreshTokenService refreshTokenService;

    private final PasswordEncoder passwordEncoder;

    // Announces that somebody's grants moved, for the auth domain's cache to
    // act on. An event rather than a call to that cache, because the cache
    // needs this service to populate itself: a direct dependency each way is a
    // cycle Spring would refuse to construct. See AuthoritiesChanged.
    private final ApplicationEventPublisher events;

    public UserService(UserRepository repository,
                       RoleService roleService,
                       RefreshTokenService refreshTokenService,
                       PasswordEncoder passwordEncoder,
                       ApplicationEventPublisher events) {
        this.repository = repository;
        this.roleService = roleService;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    public Page<User> list(ListParams params) {
        return repository.findAll(
                UserSpecifications.from(params),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    public User get(Long id) {
        return repository.findById(id)
                .filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    public List<User> getMany(List<Long> ids) {
        return repository.findAllById(ids).stream()
                .filter(user -> user.getDeletedAt() == null)
                .toList();
    }

    /**
     * Resolves the phone number typed into the sign-in form. Used by the auth
     * domain.
     *
     * <p>Normalised first, so that someone who enrolled as {@code +84901234567}
     * can sign in having typed {@code 0901234567}. A number this method cannot
     * make sense of resolves to empty rather than throwing: the caller must not
     * be able to tell a malformed number from an unknown one.
     */
    public Optional<User> findForAuthentication(String phone) {
        return PhoneNumbers.normalize(phone).flatMap(repository::findByPhone);
    }

    /** Whether an account already signs in with this (normalised) number. */
    public boolean phoneInUse(String normalizedPhone) {
        return repository.findByPhone(normalizedPhone).isPresent();
    }

    /**
     * Creates the account behind a self-registration.
     *
     * <p>Separate from {@link #create} because nothing about it is the
     * caller's choice: the role is fixed by the flow and the account is
     * active from the start. The email is where a forgotten password is
     * recovered through, and like the number it may not belong to another
     * account.
     *
     * @param roleCode the system role the flow assigns; see
     *                 {@link RoleService#getByCode}
     */
    @Transactional
    public User createSelfRegistered(String phone, String email, String rawPassword, String fullName,
                                     String roleCode) {
        if (!GUEST_ROLE_CODE.equals(roleCode)) {
            throw new AccessDeniedException("Self-registration can only create a Guest account");
        }
        return createWithSystemRole(phone, email, rawPassword, fullName, roleCode);
    }

    /**
     * Creates the account behind a new member of staff, holding the system
     * role their kind of staff gets. Same shape as a self-registration — the
     * role is decided by the caller's workflow, not chosen on a form.
     */
    @Transactional
    public User createStaffAccount(String phone, String rawPassword, String fullName, String roleCode) {
        if ("CLINIC_ADMIN".equals(roleCode)) {
            requireAuthority(Permissions.CLINICS_WRITE,
                    "Only the System Administrator can create a Clinic Administrator");
            requireActorRole(ADMIN_ROLE_CODE,
                    "Only the System Administrator can create a Clinic Administrator");
        } else if (Set.of("DOCTOR", "MANAGER", "NURSE", "MEDICAL_STAFF").contains(roleCode)) {
            requireAuthority(Permissions.EMPLOYEES_WRITE,
                    "Only a Clinic Administrator can create clinic staff");
            requireActorRole("CLINIC_ADMIN",
                    "Only a Clinic Administrator can create clinic staff");
        } else {
            throw new AccessDeniedException("This role cannot be assigned through staff creation");
        }
        return createWithSystemRole(phone, null, rawPassword, fullName, roleCode);
    }

    /**
     * Lets an account sign in, or stops it — for workflows that own an
     * account's lifecycle, such as a member of staff leaving.
     *
     * <p>Stopping it ends every session open under it and drops its cached
     * authorities, the same as disabling it on the user screen, so a token it
     * already holds stops working on the next request.
     */
    @Transactional
    public User setSignInAllowed(Long id, boolean allowed) {
        User user = get(id);
        boolean wasActive = user.canSignIn();
        UserStatus before = user.getStatus();
        if (allowed) {
            // Only undoes what this workflow did. A LOCKED account was locked
            // for a security reason by an administrator; somebody coming back
            // to work does not clear that.
            if (user.getStatus() == UserStatus.INACTIVE) {
                user.setStatus(UserStatus.ACTIVE);
            }
        } else {
            // The same guard the user screen applies: a staff account can
            // also hold ADMIN, and it must not be the last one switched off.
            requireAdminSurvives(user, user.getRoles(), UserStatus.INACTIVE);
            if (user.getStatus() == UserStatus.ACTIVE) {
                user.setStatus(UserStatus.INACTIVE);
            }
        }
        User saved = repository.save(user);
        if (wasActive && !saved.canSignIn()) {
            refreshTokenService.revokeAllFor(saved, Instant.now());
        }
        events.publishEvent(new AuthoritiesChanged.ForUser(saved.getId()));
        if (before != saved.getStatus()) {
            events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("users", saved.getId(), "status_change",
                    java.util.Map.of("status", String.valueOf(before)), java.util.Map.of("status", String.valueOf(saved.getStatus()))));
        }
        return saved;
    }

    private static java.util.List<String> roleCodes(java.util.Collection<Role> roles) {
        return roles.stream().map(Role::getCode).sorted().toList();
    }

    private User createWithSystemRole(String phone, String rawEmail, String rawPassword, String fullName,
                                      String roleCode) {
        if (ADMIN_ROLE_CODE.equals(roleCode)) {
            throw new AccessDeniedException("The System Administrator account is provisioned during deployment");
        }
        String normalized = requireNormalizedPhone(phone);
        String email = normalizeEmail(rawEmail);
        requirePhoneAvailable(normalized, null);
        requireEmailAvailable(email, null);

        User user = new User(
                normalized,
                email,
                hash("password", rawPassword),
                fullName.trim(),
                UserStatus.ACTIVE);
        user.setRoles(new LinkedHashSet<>(List.of(roleService.getByCode(roleCode))));
        return repository.save(user);
    }

    @Transactional
    public User create(UserRequest request) {
        String phone = requireNormalizedPhone(request.phone());
        String email = normalizeEmail(request.email());

        requirePhoneAvailable(phone, null);
        requireEmailAvailable(email, null);

        User user = new User(
                phone,
                email,
                hash("password", request.password()),
                request.fullName().trim(),
                request.status() == null ? UserStatus.ACTIVE : request.status());
        Set<Role> roles = resolveRoles(request.roles());
        requireRoleCreationWorkflow(roles);
        user.setRoles(roles);

        return repository.save(user);
    }

    @Transactional
    public User update(Long id, UserPatchRequest request) {
        User user = get(id);

        boolean phoneChanged = false;
        if (request.phone() != null) {
            String phone = requireNormalizedPhone(request.phone());
            requirePhoneAvailable(phone, user.getId());
            // Compared normalised, so a form re-sending the same number in
            // another spelling does not count as a change.
            phoneChanged = !phone.equals(user.getPhone());
            user.setPhone(phone);
        }
        // Absent means "leave it alone"; an empty string means "remove it",
        // which normalizeEmail turns into the null the column holds. That is
        // the only way to clear an address, and it is what the edit screen
        // sends once the field has been emptied.
        if (request.email() != null) {
            String email = normalizeEmail(request.email());
            requireEmailAvailable(email, user.getId());
            user.setEmail(email);
        }
        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
        }

        // Both of these can take the last administrator away, so they are
        // checked against the state the request is asking for, not the state
        // the row is currently in.
        Set<Role> roles = request.roles() == null ? user.getRoles() : resolveRoles(request.roles());
        if (request.roles() != null) {
            requireRoleChangeWorkflow(user, roles);
        }
        UserStatus status = request.status() == null ? user.getStatus() : request.status();
        requireAdminSurvives(user, roles, status);

        boolean wasActive = user.canSignIn();
        java.util.List<String> rolesBefore = roleCodes(user.getRoles());
        UserStatus statusBefore = user.getStatus();
        if (request.roles() != null) {
            user.setRoles(roles);
        }
        user.setStatus(status);
        User saved = repository.save(user);
        if (request.roles() != null && !rolesBefore.equals(roleCodes(saved.getRoles()))) {
            events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("users", saved.getId(), "role_change",
                    java.util.Map.of("roles", rolesBefore), java.util.Map.of("roles", roleCodes(saved.getRoles()))));
        }
        if (statusBefore != saved.getStatus()) {
            events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("users", saved.getId(), "status_change",
                    java.util.Map.of("status", String.valueOf(statusBefore)), java.util.Map.of("status", String.valueOf(saved.getStatus()))));
        }

        // An account that just stopped being able to sign in must also stop
        // being able to renew a token it already holds. So must one whose
        // sign-in number moved: the usual reason is that the old number went
        // to somebody else or the account was taken over, the same reasons a
        // password reset ends every session.
        if ((wasActive && !saved.canSignIn()) || phoneChanged) {
            refreshTokenService.revokeAllFor(saved, Instant.now());
        }

        // Only when the request touched something authorisation depends on.
        // Correcting a surname changes nothing about what the account may do,
        // and evicting on it would throw away a cache entry for no reason.
        if (request.roles() != null || request.status() != null) {
            events.publishEvent(new AuthoritiesChanged.ForUser(saved.getId()));
        }
        return saved;
    }

    /**
     * Replaces the roles an account holds among {@code scope} with {@code codes},
     * keeping every other role it holds (SRS 3.18.10: a clinic administrator
     * sets roles at the clinic and nothing else). The same guards, record and
     * cache eviction as a role change on the user screen.
     */
    @Transactional
    public User replaceRolesWithin(Long id, java.util.Set<String> scope, java.util.Set<String> codes) {
        User user = get(id);
        java.util.List<String> rolesBefore = roleCodes(user.getRoles());
        Set<Role> roles = new LinkedHashSet<>();
        for (Role role : user.getRoles()) {
            if (!scope.contains(role.getCode())) {
                roles.add(role);
            }
        }
        for (String code : codes) {
            roles.add(roleService.getByCode(code));
        }
        requireAdminSurvives(user, roles, user.getStatus());
        user.setRoles(roles);
        User saved = repository.save(user);
        if (!rolesBefore.equals(roleCodes(saved.getRoles()))) {
            events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("users", saved.getId(), "role_change",
                    java.util.Map.of("roles", rolesBefore), java.util.Map.of("roles", roleCodes(saved.getRoles()))));
            events.publishEvent(new AuthoritiesChanged.ForUser(saved.getId()));
        }
        return saved;
    }

    /**
     * Sets somebody else's password, as an administrator.
     *
     * <p>Every session open under the account is ended, because the usual
     * reason to do this is that the account may be in the wrong hands.
     */
    @Transactional
    public User setPassword(Long id, String rawPassword) {
        User user = get(id);
        user.setPasswordHash(hash("password", rawPassword));
        User saved = repository.save(user);
        refreshTokenService.revokeAllFor(saved, Instant.now());
        events.publishEvent(com.vn.vitalcare.share.audit.AuditEvent.of("users", saved.getId(), "password_reset"));
        return saved;
    }

    /**
     * Changes the signed-in user's own password, having checked the current one.
     *
     * <p>Requiring the current password is what makes this safe on a machine
     * somebody walked away from: a stolen session alone should not be enough to
     * lock the real owner out of their account.
     */
    @Transactional
    public User changeOwnPassword(Long id, String currentPassword, String newPassword) {
        User user = get(id);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ConflictException("The current password is not correct");
        }
        user.setPasswordHash(hash("newPassword", newPassword));
        User saved = repository.save(user);
        // Other sessions end; the one making the change gets a fresh pair back
        // from the controller, so the person changing their password is not
        // signed out by their own action.
        refreshTokenService.revokeAllFor(saved, Instant.now());
        return saved;
    }

    /**
     * Every password is hashed here. BCrypt reads at most 72 bytes and refuses a
     * longer password with an exception that would reach the client as a raw
     * library message; accented letters take two or three bytes each, so 72
     * characters can still be too long.
     */
    private String hash(String field, String rawPassword) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new FieldValidationException(field, "Password must be between 8 and 72 characters");
        }
        return passwordEncoder.encode(rawPassword);
    }

    /** Stamps the successful sign-in. Called by the auth domain. */
    @Transactional
    public void recordLogin(User user, Instant when) {
        user.setLastLoginAt(when);
        repository.save(user);
    }

    /** Keeps the account's Guest/Patient role aligned with its patient profile lifecycle. */
    @Transactional
    public void synchronizeCustomerRole(User user, boolean isPatient) {
        Set<Role> roles = new LinkedHashSet<>(user.getRoles());
        List<String> before = roleCodes(roles);
        roles.removeIf(role -> GUEST_ROLE_CODE.equals(role.getCode()) || PATIENT_ROLE_CODE.equals(role.getCode()));
        roles.add(roleService.getByCode(isPatient ? PATIENT_ROLE_CODE : GUEST_ROLE_CODE));
        if (!before.equals(roleCodes(roles))) {
            user.setRoles(roles);
            User saved = repository.save(user);
            events.publishEvent(new AuthoritiesChanged.ForUser(saved.getId()));
        }
    }

    @Transactional
    public User delete(Long id) {
        User user = get(id);

        // Deleting the account you are signed in as is almost always a slip,
        // and it is unrecoverable. Disabling it is the reversible version of
        // whatever was intended.
        if (CurrentUser.id().filter(user.getId()::equals).isPresent()) {
            throw new ConflictException("You cannot delete the account you are signed in as");
        }
        requireAdminSurvives(user, Set.of(), UserStatus.INACTIVE);

        Instant deletedAt = Instant.now();
        user.setStatus(UserStatus.INACTIVE);
        user.setDeletedAt(deletedAt);
        User archived = repository.save(user);
        refreshTokenService.revokeAllFor(archived, deletedAt);
        events.publishEvent(new com.vn.vitalcare.share.audit.AuditEvent("users", archived.getId(), "delete",
                java.util.Map.of("phone", archived.getPhone(), "fullName", archived.getFullName()), null));
        events.publishEvent(new AuthoritiesChanged.ForUser(archived.getId()));

        // Keep referenced customer and employee rows intact; the tombstone
        // removes the account from user administration and authentication.
        return archived;
    }

    private Set<Role> resolveRoles(List<RoleRef> refs) {
        List<Long> ids = refs.stream().map(RoleRef::id).toList();
        return new LinkedHashSet<>(roleService.resolveAll(ids));
    }

    /**
     * Refuses a change that would leave no active administrator.
     *
     * @param user   the account being changed
     * @param roles  the roles it would hold afterwards
     * @param status the status it would have afterwards
     */
    private void requireAdminSurvives(User user, Set<Role> roles, UserStatus status) {
        boolean wasAdmin = user.roleCodes().contains(ADMIN_ROLE_CODE);
        boolean staysAdmin = roles.stream().anyMatch(role -> ADMIN_ROLE_CODE.equals(role.getCode()))
                && status == UserStatus.ACTIVE;

        if (wasAdmin && !staysAdmin) {
            throw new ConflictException(
                    "The single System Administrator account cannot be demoted, disabled or deleted through account management");
        }
    }

    /** Core roles are assigned by registration, patient activation or clinic staff creation. */
    private static void requireRoleCreationWorkflow(Set<Role> roles) {
        if (roles.stream().anyMatch(role -> WORKFLOW_ROLE_CODES.contains(role.getCode()))) {
            throw new AccessDeniedException(
                    "System roles are assigned by registration or the authorized clinic staff workflow");
        }
    }

    /** Generic user editing cannot grant or remove a core role. */
    private static void requireRoleChangeWorkflow(User user, Set<Role> roles) {
        Set<String> before = user.roleCodes().stream().filter(WORKFLOW_ROLE_CODES::contains)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> after = roles.stream().map(Role::getCode).filter(WORKFLOW_ROLE_CODES::contains)
                .collect(java.util.stream.Collectors.toSet());
        if (!before.equals(after)) {
            throw new AccessDeniedException("System roles can only be changed through their designated workflow");
        }
    }

    private static void requireAuthority(String authority, String message) {
        if (!CurrentUser.has(authority)) {
            throw new AccessDeniedException(message);
        }
    }

    private void requireActorRole(String roleCode, String message) {
        Long actorId = CurrentUser.id().orElseThrow(() -> new AccessDeniedException(message));
        if (!get(actorId).roleCodes().contains(roleCode)) {
            throw new AccessDeniedException(message);
        }
    }

    /**
     * {@link PhoneNumbers#normalize}, for a write, where a number that cannot
     * be read must not be stored at all.
     *
     * <p>Unreachable from an HTTP request on purpose: the DTO pattern accepts a
     * subset of what {@link PhoneNumbers#normalize} does, so anything that clears
     * validation also normalises. What it guarantees is that the two cannot
     * drift apart quietly — if an edit to either ever opens a gap, this fails
     * loudly rather than writing a second spelling of one number into a column
     * whose whole purpose is that there is only ever one.
     *
     * <p>The offending value is not echoed back: it is unbounded caller input,
     * and validation's per-field message is the better answer anyway.
     */
    private static String requireNormalizedPhone(String raw) {
        return PhoneNumbers.normalize(raw)
                .orElseThrow(() -> new IllegalArgumentException("Not a valid Vietnamese phone number"));
    }

    /** Null stays null — an account without an email address is a valid one. */
    private static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }

    /** Refuses a number another account signs in with: registration asks before sending a code (UC-04 D2). */
    public void requirePhoneUnregistered(String phone) {
        requirePhoneAvailable(phone, null);
    }

    // Neither of these echoes the value back. The caller supplied it, so
    // repeating it says nothing they do not know, while putting a patient's
    // phone number or address into an error body puts it into every client log
    // and monitoring tool that error passes through.
    private void requirePhoneAvailable(String phone, Long selfId) {
        repository.findByPhone(phone).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException("Số điện thoại đã được sử dụng. Xin vui lòng nhập một số khác.");
            }
        });
    }

    private void requireEmailAvailable(String email, Long selfId) {
        if (email == null) {
            return;
        }
        repository.findByEmailIgnoreCase(email).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException("That email address is already in use");
            }
        });
    }
}
