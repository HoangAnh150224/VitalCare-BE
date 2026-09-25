package com.vn.vitalcare.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /auth/refresh} and {@code POST /auth/logout} payload.
 *
 * <p>The refresh token travels in the body rather than in the
 * {@code Authorization} header, because the header on those two calls is either
 * absent or holds an access token that has already expired.
 */
public record RefreshRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken) {
}
