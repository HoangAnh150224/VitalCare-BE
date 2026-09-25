package com.vn.vitalcare.identity.auth.service;

import com.vn.vitalcare.identity.auth.dto.AuthoritiesResponse;
import com.vn.vitalcare.identity.auth.dto.ChangePasswordRequest;
import com.vn.vitalcare.identity.auth.dto.CurrentUserResponse;
import com.vn.vitalcare.identity.auth.dto.LoginRequest;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.JwtService;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing in, renewing and signing out.
 *
 * <p>The pair of tokens it hands out divide the work deliberately. The access
 * token is short-lived and says only who the caller is, so it stays the same
 * size however large the permission model grows. The refresh token is
 * long-lived, opaque and recorded, which is what makes ending a session
 * possible at all. Neither property is achievable in one token.
 *
 * <p>Neither token says what the caller may do. That is answered separately, by
 * {@link #authorities()} for the UI and by {@code AuthorizationService} for the
 * API itself.
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    /**
     * Hash of nothing in particular, used to keep a failed sign-in as slow as a
     * successful one.
     *
     * <p>Without it, an unknown username returns immediately while a known one
     * pays for a BCrypt comparison first — a difference large enough to time
     * from across a network, which turns the login form into a way to enumerate
     * accounts. Verifying against this dummy hash spends the same work either
     * way.
     */
    private static final String DUMMY_HASH =
            "$2a$10$ff7CTQvwMv0Szb.qGXF3zeMUkV8Jjta52A6kYzGDeZY4YE2pyd.YS";

    /** The same answer for every way of failing, for the same reason. */
    private static final String INVALID_CREDENTIALS = "Incorrect username or password";

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    // Answers "what may this account do" from memory. Reading it here rather
    // than from the User row is what stops /auth/permissions resolving the same
    // account twice — once in the converter that authenticated the request, and
    // once again to build the reply.
    private final AuthorizationService authorizationService;

    // Which resources this account is genuinely narrowed on, so that an empty
    // list can say why it is empty rather than looking like a system with no
    // data in it. Answered from the policy cache, so it costs no query either.
    private final RowLevelSecurity rowLevel;

    public AuthService(UserService userService,
                       RefreshTokenService refreshTokenService,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       AuthorizationService authorizationService,
                       RowLevelSecurity rowLevel) {
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.authorizationService = authorizationService;
        this.rowLevel = rowLevel;
    }

    /**
     * Verifies credentials and opens a session.
     *
     * @throws BadCredentialsException on a wrong username, a wrong password or
     *                                 an account that is not active — the same
     *                                 exception and the same message for all
     *                                 three, so that a failed attempt reveals
     *                                 nothing about which accounts exist
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        Optional<User> found = userService.findForAuthentication(request.username());

        String hash = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        User user = found
                .filter(candidate -> passwordMatches)
                .filter(User::canSignIn)
                .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

        Instant now = Instant.now();
        userService.recordLogin(user, now);
        return issue(user, now);
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * <p>Re-reads the account rather than trusting the old token's claims, so a
     * role change or a disabled account takes effect on the next refresh
     * instead of surviving as long as the session does.
     */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        Instant now = Instant.now();
        User user = refreshTokenService.consume(refreshToken, now);

        if (!user.canSignIn()) {
            // The account was disabled while the session was open. Consuming
            // the token above already burned it, so this ends the session.
            throw new BadCredentialsException("This account is no longer active");
        }
        return issue(user, now);
    }

    /**
     * Ends one session.
     *
     * <p>Never fails, whatever it is handed: signing out has to work from a
     * client whose token is already expired, already revoked, or simply
     * garbage.
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revoke(refreshToken, Instant.now());
        }
    }

    /** The signed-in identity, re-read from the database rather than from the token. */
    public CurrentUserResponse me() {
        return CurrentUserResponse.from(currentUser());
    }

    /**
     * The roles and permission codes the caller currently holds.
     *
     * <p>Answered from {@link AuthorityCache}, so it reflects a role changed a
     * second ago without costing a query.
     */
    public AuthoritiesResponse authorities() {
        Long id = CurrentUser.id().orElseThrow(() -> new BadCredentialsException("Not signed in"));
        return authorizationService.entryOf(id)
                .map(entry -> AuthoritiesResponse.from(entry, rowLevel.scopedResourcesFor(id)))
                .orElseThrow(() -> new BadCredentialsException("This account no longer exists"));
    }

    /**
     * Changes the signed-in user's own password and issues a fresh pair.
     *
     * <p>Every other session under the account ends. Handing back a new pair
     * here is what stops that from signing out the person who just made the
     * change.
     */
    @Transactional
    public TokenResponse changePassword(ChangePasswordRequest request) {
        User user = currentUser();
        User updated = userService.changeOwnPassword(
                user.getId(), request.currentPassword(), request.newPassword());
        return issue(updated, Instant.now());
    }

    private TokenResponse issue(User user, Instant now) {
        return TokenResponse.of(
                jwtService.issueAccessToken(user, now),
                refreshTokenService.issue(user, now),
                jwtService.accessTokenTtlSeconds(),
                CurrentUserResponse.from(user));
    }

    /**
     * The account behind the current request.
     *
     * <p>The id comes from a token this application signed, so an id with no
     * row behind it means the account was deleted mid-session; that is a
     * credentials failure, not a 404 about a resource the caller asked for.
     */
    private User currentUser() {
        Long id = CurrentUser.id()
                .orElseThrow(() -> new BadCredentialsException("Not signed in"));
        try {
            return userService.get(id);
        } catch (ResourceNotFoundException e) {
            throw new BadCredentialsException("This account no longer exists");
        }
    }
}
