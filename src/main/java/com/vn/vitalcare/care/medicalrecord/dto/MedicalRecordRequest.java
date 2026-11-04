package com.vn.vitalcare.care.medicalrecord.dto;

import jakarta.validation.constraints.Size;

/** {@code PUT /customers/{id}/medical-record}: the whole health background, replaced as written. */
public record MedicalRecordRequest(
        @Size(max = 32, message = "Blood type must be at most 32 characters")
        String bloodType,

        @Size(max = 5000, message = "Allergies must be at most 5000 characters")
        String allergies,

        @Size(max = 10000, message = "Medical history must be at most 10000 characters")
        String medicalHistory,

        @Size(max = 10000, message = "Family history must be at most 10000 characters")
        String familyHistory,

        @Size(max = 5000, message = "Current medications must be at most 5000 characters")
        String currentMedications,

        @Size(max = 10000, message = "Note must be at most 10000 characters")
        String note,

        /** The version the editor loaded; a save over a newer one is refused instead of overwriting it. */
        Integer version) {
}
