package com.vn.vitalcare.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /auth/login} payload.
 *
 * <p>The field is called {@code username} but accepts an email address too, so
 * that nobody has to remember which of the two this system decided to key on.
 * No length or format rules: a credential is either right or wrong, and telling
 * someone their guess was too short only helps them guess better.
 */
public record LoginRequest(
        @NotBlank(message = "Username or email is required")
        String username,

        @NotBlank(message = "Password is required")
        String password) {
}
