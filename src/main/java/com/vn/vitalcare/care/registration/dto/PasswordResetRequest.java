package com.vn.vitalcare.care.registration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/password-reset}: the code sent to the number, and the
 * password to set with it. One request, as with {@link RegisterRequest}, so
 * there is no "verified, password not yet chosen" state to expire or abuse.
 */
public record PasswordResetRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
        String phone,

        // One of otp / firebaseToken proves the number: our code, or Firebase's
        // token once Firebase sent the code (OtpResponse.channel says which).
        @Pattern(regexp = "^\\d{4,8}$", message = "The code is the digits from the message")
        String otp,

        @Size(max = 4096, message = "The verification token is too long")
        String firebaseToken,

        // 72 is BCrypt's limit in bytes, which the service also checks.
        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String newPassword) {
}
