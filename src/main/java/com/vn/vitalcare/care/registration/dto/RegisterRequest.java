package com.vn.vitalcare.care.registration.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/register}: the account to create. The phone number is
 * what it signs in with; the email is where a password-recovery code goes,
 * so it is required.
 */
public record RegisterRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
        String phone,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @NotBlank(message = "Full name is required")
        @Size(max = 255, message = "Full name must be at most 255 characters")
        String fullName,

        // 72 is BCrypt's limit in bytes, which the service also checks: a
        // password of Vietnamese letters reaches it well before 72 characters.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password) {
}
