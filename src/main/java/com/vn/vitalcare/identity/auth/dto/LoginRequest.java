package com.vn.vitalcare.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /auth/login} payload.
 *
 * <p>Any spelling of the number is accepted — {@code 0901234567} and
 * {@code +84901234567} reach the same account, because the service normalises
 * before it looks anything up. No format rule here on purpose: a credential is
 * either right or wrong, and telling someone their guess was malformed
 * separates "no such account" from "wrong shape", which only helps them guess
 * better.
 */
public record LoginRequest(
        @NotBlank(message = "Phone number is required")
        String phone,

        @NotBlank(message = "Password is required")
        String password) {
}
