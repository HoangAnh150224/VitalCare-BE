package com.vn.vitalcare.identity.auth.service;

import java.util.Collection;
import java.util.Optional;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;

/**
 * Turns the identity in an access token into the authorities
 * {@code @PreAuthorize} reads.
 *
 * <p>This is the half of authorisation that used to live in the token itself.
 * Moving it out is what keeps the token a constant few hundred bytes however
 * large the permission model grows; keeping the answer in
 * {@link AuthorityCache} rather than in the database is what stops that costing
 * a query per request.
 *
 * <p>It lives in the {@code identity.auth} slice and reaches the user domain
 * through {@code UserService}, never its repository.
 */
@Service
public class AuthorizationService {

    private final AuthorityCache cache;

    public AuthorizationService(AuthorityCache cache) {
        this.cache = cache;
    }

    /**
     * The authorities the account behind {@code subject} currently holds.
     *
     * @param subject the token's {@code sub} claim, which this application
     *                always writes as the user id
     * @throws InvalidBearerTokenException when the token names an account that
     *                                     cannot make requests any more
     */
    public Collection<GrantedAuthority> authoritiesOf(String subject) {
        AuthorityCache.Entry entry = cache.get(parse(subject))
                .orElseThrow(() -> new InvalidBearerTokenException("This account no longer exists"));

        if (!entry.canSignIn()) {
            throw new InvalidBearerTokenException("This account is no longer active");
        }
        return entry.authorities();
    }

    /**
     * The stamp of the caller's current grants, for the {@code X-Auth-Version}
     * response header.
     *
     * @see AuthorityCache#versionFor(long)
     */
    public String versionOf(long userId) {
        return cache.versionFor(userId);
    }

    /**
     * What an account may do, in the shape the client stores.
     *
     * <p>Served from the cache, so {@code GET /auth/permissions} costs no
     * database read either.
     */
    public Optional<AuthorityCache.Entry> entryOf(long userId) {
        return cache.get(userId);
    }

    /**
     * Whether an account holds a permission right now — asked, and answered,
     * without a request in flight.
     *
     * <p>Returns {@code false} rather than throwing for an account that is
     * disabled, deleted or simply unknown. The caller is a delivery path, not a
     * request: there is nobody to return an error to, and the right behaviour is
     * to send nothing.
     */
    public boolean has(long userId, String permission) {
        return cache.get(userId)
                .filter(AuthorityCache.Entry::canSignIn)
                .filter(entry -> entry.authorities().stream()
                        .anyMatch(granted -> permission.equals(granted.getAuthority())))
                .isPresent();
    }

    private static long parse(String subject) {
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("Malformed access token");
        }
    }
}
