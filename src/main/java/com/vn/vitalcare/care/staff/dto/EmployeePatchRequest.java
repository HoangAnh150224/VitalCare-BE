package com.vn.vitalcare.care.staff.dto;

import com.vn.vitalcare.entity.EmployeeStatus;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /employees/{id}}: the professional record, and whether the
 * person still works here. Null leaves a field alone; an empty string clears a
 * text field.
 *
 * <p>Name and phone number belong to the account and are changed on the user
 * screen. The staff type is not here: it decides the role, and changing what
 * somebody is is a new record, not an edit.
 */
public record EmployeePatchRequest(
        @Size(max = 255, message = "Specialty must be at most 255 characters")
        String specialty,

        @Size(max = 255, message = "Title must be at most 255 characters")
        String professionalTitle,

        @Size(max = 128, message = "Licence number must be at most 128 characters")
        String licenseNo,

        @Size(max = 128, message = "Position must be at most 128 characters")
        String clinicPosition,

        EmployeeStatus status,
        @jakarta.validation.constraints.Pattern(regexp = "^[+0-9 .()-]{10,20}$")
        String phone,
        @Size(min = 1, max = 255) String fullName,
        @Size(min = 8, max = 72) String password,
        java.util.UUID clinicId) {
    public EmployeePatchRequest(String specialty, String professionalTitle, String licenseNo,
                                String clinicPosition, EmployeeStatus status) {
        this(specialty, professionalTitle, licenseNo, clinicPosition, status, null, null, null, null);
    }
}
