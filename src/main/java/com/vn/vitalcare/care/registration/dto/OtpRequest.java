package com.vn.vitalcare.care.registration.dto;

import jakarta.validation.constraints.NotBlank;

/** A Vietnamese phone number to which the requested one-time code will be sent. */
public record OtpRequest(
        @NotBlank(message = "Phone number is required")
        // The service validates the shape so it can report too few/many digits,
        // invalid characters and unsupported country codes separately.
        String phone) {
}
