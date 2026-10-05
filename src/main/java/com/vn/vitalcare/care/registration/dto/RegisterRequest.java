package com.vn.vitalcare.care.registration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/register}: the code just received, and the account to
 * create with it. One request rather than "verify" then "create", so there is
 * no half-registered state — a verified number with no account — to expire,
 * resume or abuse.
 */
public record RegisterRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
        String phone,

        @NotBlank(message = "The code is required")
        @Pattern(regexp = "^\\d{4,8}$", message = "The code is the digits from the message")
        String otp,

        @NotBlank(message = "Full name is required")
        @Size(max = 255, message = "Full name must be at most 255 characters")
        String fullName,

        // 72 is BCrypt's limit in bytes, which the service also checks: a
        // password of Vietnamese letters reaches it well before 72 characters.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password) {
}
