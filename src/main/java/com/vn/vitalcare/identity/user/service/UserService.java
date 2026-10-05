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
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    /**
     * The role the system cannot afford to have nobody holding.
     *
     * <p>Only {@code users:write} and {@code roles:write} can restore access
     * once they are gone, and both are granted through this role in the seed
     * data — so the last active holder of it is protected from being demoted,
     * disabled or deleted.
     */
    private static final String ADMIN_ROLE_CODE = "ADMIN";

    /** Sort properties the list endpoint accepts; anything else is ignored. */
    private static final Set<String> SORTABLE =
            Set.of("id", "phone", "email", "fullName", "status", "createdAt", "lastLoginAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /**
     * The spellings of a Vietnamese mobile number this system accepts, with the
     * nine significant digits captured.
     *
     * <p>Carrier prefixes are deliberately not enumerated. A list of them has
     * to be edited every time Vietnam allocates a new one, and the cost of
     * that maintenance outweighs catching a number with a prefix nobody issues
     * — which is a wrong number either way, and a thing business validation
     * can take up later if it ever matters.
     */
    private static final Pattern PHONE = Pattern.compile("^(?:\\+84|84|0)(\\d{9})$");

    /**
     * What people put between the digits, and nothing else.
     *
     * <p>{@code \h} as well as {@code \s}, because Java's {@code \s} is ASCII
     * only, and a number copied from a web page or a chat app routinely
     * carries a no-break space ({@code U+00A0}, {@code U+202F}). Without it a
     * correctly typed number answers "incorrect phone number or password".
     */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\h.()-]");

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
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    public List<User> getMany(List<Long> ids) {
        return repository.findAllById(ids);
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
        return normalizePhone(phone).flatMap(repository::findByPhone);
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
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                request.status() == null ? UserStatus.ACTIVE : request.status());
        user.setRoles(resolveRoles(request.roles()));

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
        UserStatus status = request.status() == null ? user.getStatus() : request.status();
        requireAdminSurvives(user, roles, status);

        if (request.roles() != null) {
            user.setRoles(roles);
        }

        boolean wasActive = user.canSignIn();
        user.setStatus(status);
        User saved = repository.save(user);

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
     * Sets somebody else's password, as an administrator.
     *
     * <p>Every session open under the account is ended, because the usual
     * reason to do this is that the account may be in the wrong hands.
     */
    @Transactional
    public User setPassword(Long id, String rawPassword) {
        User user = get(id);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        User saved = repository.save(user);
        refreshTokenService.revokeAllFor(saved, Instant.now());
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
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        User saved = repository.save(user);
        // Other sessions end; the one making the change gets a fresh pair back
        // from the controller, so the person changing their password is not
        // signed out by their own action.
        refreshTokenService.revokeAllFor(saved, Instant.now());
        return saved;
    }

    /** Stamps the successful sign-in. Called by the auth domain. */
    @Transactional
    public void recordLogin(User user, Instant when) {
        user.setLastLoginAt(when);
        repository.save(user);
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

        repository.delete(user);
        // The cache would find no row and drop the entry on its own, but only
        // when something next asks for it. Saying so outright means a token
        // held by the deleted account stops working immediately rather than at
        // the next lookup.
        events.publishEvent(new AuthoritiesChanged.ForUser(user.getId()));

        // Returned so the controller can echo the deleted row.
        return user;
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
        boolean wasAdmin = user.roleCodes().contains(ADMIN_ROLE_CODE) && user.canSignIn();
        boolean staysAdmin = roles.stream().anyMatch(role -> ADMIN_ROLE_CODE.equals(role.getCode()))
                && status == UserStatus.ACTIVE;

        if (wasAdmin && !staysAdmin && repository.countActiveWithRole(ADMIN_ROLE_CODE) <= 1) {
            throw new ConflictException(
                    "This is the last active administrator; promote another account first");
        }
    }

    /**
     * Converts any accepted spelling of a Vietnamese mobile number to the one
     * form the column stores, E.164 — {@code 0901234567}, {@code 84901234567}
     * and {@code +84 901 234 567} all become {@code +84901234567}.
     *
     * <p>This is what makes {@code uq_users_phone} mean "one account per
     * number". Without it the constraint is satisfied by four spellings of the
     * same line, and which account a sign-in finds depends on how the person
     * happened to type it.
     *
     * <p>Empty when the value is not a number this system can key on.
     */
    private static Optional<String> normalizePhone(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = PHONE.matcher(SEPARATORS.matcher(raw).replaceAll(""));
        return matcher.matches() ? Optional.of("+84" + matcher.group(1)) : Optional.empty();
    }

    /**
     * As above, for a write, where a number that cannot be read must not be
     * stored at all.
     *
     * <p>Unreachable from an HTTP request on purpose: the DTO pattern accepts a
     * subset of what {@link #normalizePhone} does, so anything that clears
     * validation also normalises. What it guarantees is that the two cannot
     * drift apart quietly — if an edit to either ever opens a gap, this fails
     * loudly rather than writing a second spelling of one number into a column
     * whose whole purpose is that there is only ever one.
     *
     * <p>The offending value is not echoed back: it is unbounded caller input,
     * and validation's per-field message is the better answer anyway.
     */
    private static String requireNormalizedPhone(String raw) {
        return normalizePhone(raw)
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

    // Neither of these echoes the value back. The caller supplied it, so
    // repeating it says nothing they do not know, while putting a patient's
    // phone number or address into an error body puts it into every client log
    // and monitoring tool that error passes through.
    private void requirePhoneAvailable(String phone, Long selfId) {
        repository.findByPhone(phone).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException("That phone number is already in use");
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
