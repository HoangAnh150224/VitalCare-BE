package com.vn.vitalcare.care.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /clinical_encounters} and {@code PATCH /clinical_encounters/{id}}.
 *
 * <p>{@code customerId} and {@code appointmentId} say whose visit this is and
 * are read on create only: an encounter does not move to another patient.
 */
public record EncounterRequest(
        Long customerId,

        Long appointmentId,

        @NotBlank(message = "The reason for the visit is required")
        @Size(max = 5000, message = "Symptoms must be at most 5000 characters")
        String chiefComplaint,

        @Size(max = 10000, message = "Clinical notes must be at most 10000 characters")
        String clinicalNote,

        @Size(max = 5000, message = "Conclusion must be at most 5000 characters")
        String diagnosis,

        @Size(max = 5000, message = "Follow-up instructions must be at most 5000 characters")
        String followUp) {
}
