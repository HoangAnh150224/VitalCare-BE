package com.vn.vitalcare.care.clinic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /clinics} and {@code PUT /clinics/{id}}: the same three fields either way. */
public record ClinicRequest(
        @NotBlank(message = "Clinic name is required")
        @Size(max = 255, message = "Clinic name must be at most 255 characters")
        String name,

        @Size(max = 500, message = "Address must be at most 500 characters")
        String address,

        @Size(max = 50, message = "Phone must be at most 50 characters")
        String contactPhone) {
}
