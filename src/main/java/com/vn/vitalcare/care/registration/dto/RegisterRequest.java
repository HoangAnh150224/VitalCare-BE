package com.vn.vitalcare.care.registration.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;

/**
 * {@code POST /auth/register}: the account to create after proving control of
 * its phone number with a one-time code.
 * The email is optional; when given it must be valid and not another account's.
 */
public record RegisterRequest(
        // The shape is checked by the service, which says which way it is wrong (PhoneNumbers.problem).
        @NotBlank(message = "Phone number is required")
        String phone,

        @NotBlank(message = "The code is required")
        @Pattern(regexp = "^\\d{4,8}$", message = "The code is the digits from the SMS")
        String otp,

        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @NotBlank(message = "Full name is required")
        @Size(max = 255, message = "Full name must be at most 255 characters")
        // Letters (any script, with their accents) and the spaces between words.
        @Pattern(regexp = "^\\s*[\\p{L}\\p{M}]+(?:\\s+[\\p{L}\\p{M}]+)*\\s*$",
                message = "Full name may only contain letters and spaces")
        String fullName,

        /** Optional unless a patient profile already exists for this verified phone and name. */
        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth,

        // 72 is BCrypt's limit in bytes, which the service also checks: a
        // password of Vietnamese letters reaches it well before 72 characters.
        // Not @NotBlank: a password of spaces is refused for what it is, by the service.
        @NotEmpty(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password) {

    /** Keeps callers compiled against the pre-profile-link request shape working. */
    public RegisterRequest(String phone, String otp, String email, String fullName, String password) {
        this(phone, otp, email, fullName, null, password);
    }
}
