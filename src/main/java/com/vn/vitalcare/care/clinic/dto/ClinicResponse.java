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
        String operatingStatus) {

    public static ClinicResponse from(Clinic clinic) {
        return new ClinicResponse(
                clinic.getClinicId(),
                clinic.getClinicName(),
                clinic.getAddress(),
                clinic.getContactPhone(),
                clinic.getOperatingStatus());
    }
}
