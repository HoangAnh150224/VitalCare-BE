package com.vn.vitalcare.care.registration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /auth/password-reset/otp/check}: is this the code texted to the
 * number? Checked without being spent; {@code /auth/password-reset} still
 * takes it again with the new password.
 */
public record OtpCheckRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
        String phone,

        @NotBlank(message = "The code is required")
        @Pattern(regexp = "^\\d{4,8}$", message = "The code is the digits from the SMS")
        String otp) {
}
