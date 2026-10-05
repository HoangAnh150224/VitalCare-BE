package com.vn.vitalcare.identity.auth.dto;

import com.vn.vitalcare.identity.user.entity.User;

/**
 * The signed-in identity, as {@code GET /auth/me} and every token response
 * report it.
 *
 * <p>Identity only: who the account is, not what it may do. Roles and
 * permissions are a separate payload behind a separate endpoint
 * ({@link AuthoritiesResponse}, {@code GET /auth/permissions}), for the same
 * reason they are no longer stamped into the access token.
 *
 * <p>Narrower than the user resource's own response and declared separately
 * rather than imported: this is the shape of "who am I", and widening what an
 * administrator can see about somebody else should not widen it.
 */
public record CurrentUserResponse(
        Long id,
        String phone,
        String email,
        String fullName) {

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(
                user.getId(),
                user.getPhone(),
                user.getEmail(),
                user.getFullName());
    }
}
