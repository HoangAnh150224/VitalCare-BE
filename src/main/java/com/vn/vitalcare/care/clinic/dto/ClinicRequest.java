package com.vn.vitalcare.care.clinic.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /clinics} and {@code PUT /clinics/{id}} (SRS 3.18.2, 3.18.4): the same fields either way. */
public record ClinicRequest(
        @NotBlank(message = "Clinic name is required")
        @Size(max = 255, message = "Clinic name must be at most 255 characters")
        String name,

        @NotBlank(message = "Address is required")
        @Size(max = 500, message = "Address must be at most 500 characters")
        String address,

        @NotBlank(message = "Contact phone is required")
        @Size(max = 50, message = "Phone must be at most 50 characters")
        String contactPhone,

        @NotBlank(message = "Contact email is required")
        @Email(message = "Contact email is not valid")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String contactEmail,

        @Size(max = 255, message = "Contact person must be at most 255 characters")
        String contactPerson,

        /** OPERATING or INACTIVE; OPERATING when left out. An inactive clinic takes no new visits. */
        @Pattern(regexp = "^$|^(OPERATING|INACTIVE)$", message = "Status must be OPERATING or INACTIVE")
        String operatingStatus) {
}
