package com.vn.vitalcare.care.medicalrecord.dto;

import com.vn.vitalcare.entity.MedicalRecord;
import java.time.Instant;

/** A patient's health background. Every field is null for a patient nobody has written a record for yet. */
public record MedicalRecordResponse(
        Long customerId,
        String bloodType,
        String allergies,
        String medicalHistory,
        String familyHistory,
        String currentMedications,
        String note,
        /** Sent back on save, so an edit made over somebody else's is caught; null when there is no record yet. */
        Integer version,
        Instant updatedAt) {

    public static MedicalRecordResponse empty(Long customerId) {
        return new MedicalRecordResponse(customerId, null, null, null, null, null, null, null, null);
    }

    public static MedicalRecordResponse from(Long customerId, MedicalRecord record) {
        return new MedicalRecordResponse(
                customerId,
                record.getBloodType(),
                record.getAllergies(),
                record.getMedicalHistory(),
                record.getFamilyHistory(),
                record.getCurrentMedications(),
                record.getNote(),
                record.getVersion(),
                record.getUpdatedAt() != null ? record.getUpdatedAt() : record.getCreatedAt());
    }
}
