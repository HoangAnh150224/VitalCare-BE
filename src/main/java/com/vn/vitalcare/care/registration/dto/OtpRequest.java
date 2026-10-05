package com.vn.vitalcare.care.registration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code POST /auth/register/otp}: send a code to this number. */
public record OtpRequest(
        @NotBlank(message = "Phone number is required")
        // Same pattern as UserRequest, so whatever can be registered can also
        // be signed in with afterwards.
        @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
        String phone) {
}
