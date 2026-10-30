package com.vn.vitalcare.care.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /clinical_encounters/{id}/refer}: the doctor the patient sees next today, and why. */
public record ReferRequest(
        @NotNull(message = "Pick the doctor to refer to") Long employeeId,
        @NotBlank(message = "Say why the patient is referred") @Size(max = 255) String reason) {
}
