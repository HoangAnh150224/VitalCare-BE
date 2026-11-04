package com.vn.vitalcare.care.clinic.dto;

import com.vn.vitalcare.entity.Clinic;
import java.util.UUID;

/**
 * A clinic, for choosing where to book.
 *
 * <p>The key goes out as {@code id} although the column is {@code clinic_id}:
 * the admin UI's data layer addresses every record by {@code id}.
 */
public record ClinicResponse(
        UUID id,
        String name,
        String address,
        String contactPhone,
        String contactEmail,
        String contactPerson,
        /** OPERATING or INACTIVE. */
        String operatingStatus,
        /** Active staff working there; null where it was not counted. */
        Long staffCount) {

    public static ClinicResponse from(Clinic clinic) {
        return from(clinic, null);
    }

    public static ClinicResponse from(Clinic clinic, Long staffCount) {
        return new ClinicResponse(
                clinic.getClinicId(),
                clinic.getClinicName(),
                clinic.getAddress(),
                clinic.getContactPhone(),
                clinic.getContactEmail(),
                clinic.getContactPerson(),
                clinic.getOperatingStatus(),
                staffCount);
    }
}
