package com.vn.vitalcare.share.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * Who is making the current request, read off the validated access token.
 *
 * <p>A static accessor rather than a bean because it is a read of the thread's
 * {@link SecurityContextHolder}, not a collaborator anything should mock: the
 * alternative is threading an id through method signatures that have no other
 * reason to know about authentication.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The signed-in user's id, or empty on an unauthenticated request. */
    public static Optional<Long> id() {
        return jwt().map(Jwt::getSubject).map(subject -> {
            try {
                return Long.valueOf(subject);
            } catch (NumberFormatException e) {
                // A token whose subject is not a user id is not one this
                // application issued, whatever its signature says.
                return null;
            }
        });
    }

    /**
     * The signed-in user's display name, for messages and audit text.
     *
     * <p>Empty for a token minted before this claim existed, which is why
     * callers supply their own fallback rather than relying on it being there.
     */
    public static Optional<String> displayName() {
        return jwt().map(token -> token.getClaimAsString(JwtService.CLAIM_NAME));
    }

    /** True when the request carries a specific permission code. */
    public static boolean has(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> permission.equals(granted.getAuthority()));
    }

    private static Optional<Jwt> jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt token)) {
            return Optional.empty();
        }
        return Optional.of(token);
    }
}
