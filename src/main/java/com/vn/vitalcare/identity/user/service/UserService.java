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
            Set.of("id", "username", "email", "fullName", "status", "createdAt", "lastLoginAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final UserRepository repository;

    // Roles are resolved through the role domain's own service rather than its
    // repository, which also means an unknown role id produces the same 404 the
    // role endpoints would.
    private final RoleService roleService;

    // Disabling an account or resetting its password has to end the sessions
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
     * Resolves the identifier typed into the sign-in form, which may be a
     * username or an email address. Used by the auth domain.
     */
    public Optional<User> findForAuthentication(String identifier) {
        return repository.findByUsernameOrEmail(identifier.trim());
    }

    @Transactional
    public User create(UserRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        requireUsernameAvailable(username, null);
        requireEmailAvailable(email, null);

        User user = new User(
                username,
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

        if (request.username() != null) {
            String username = request.username().trim();
            requireUsernameAvailable(username, user.getId());
            user.setUsername(username);
        }
        if (request.email() != null) {
            String email = request.email().trim().toLowerCase();
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
        // being able to renew a token it already holds.
        if (wasActive && !saved.canSignIn()) {
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

    private void requireUsernameAvailable(String username, Long selfId) {
        repository.findByUsernameIgnoreCase(username).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException("The username %s is already taken".formatted(username));
            }
        });
    }

    private void requireEmailAvailable(String email, Long selfId) {
        repository.findByEmailIgnoreCase(email).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException("The email %s is already in use".formatted(email));
            }
        });
    }
}
