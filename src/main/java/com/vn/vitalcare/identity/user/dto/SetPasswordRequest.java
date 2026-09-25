package com.vn.vitalcare.identity.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /users/{id}/password} payload — an administrator setting somebody
 * else's password.
 *
 * <p>No current password is asked for, and none could be: the administrator
 * does not know it, and the point of the endpoint is to restore access to an
 * account whose password has been lost. The permission check is what authorises
 * it. Changing your <em>own</em> password goes through
 * {@code POST /api/auth/change-password} instead, which does require the
 * current one.
 */
public record SetPasswordRequest(
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
        String password) {
}
