package com.vn.vitalcare.care.staff.dto;

import com.vn.vitalcare.entity.StaffType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /employees}: a new member of staff — the account they sign in
 * with and the professional record, in one step. The role follows from
 * {@code staffType}; it is not chosen separately.
 */
public record EmployeeCreateRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[\\s\\h.()-]*(?:\\+?84|0)(?:[\\s\\h.()-]*\\d){9}[\\s\\h.()-]*$",
                message = "Phone number must be a Vietnamese number, such as 0901234567 or +84901234567")
        String phone,

        @NotBlank(message = "Full name is required")
        @Size(max = 255, message = "Full name must be at most 255 characters")
        String fullName,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password,

        @NotNull(message = "A staff type is required")
        StaffType staffType,

        @Size(max = 255, message = "Specialty must be at most 255 characters")
        String specialty,

        @Size(max = 255, message = "Title must be at most 255 characters")
        String professionalTitle,

        @Size(max = 128, message = "Licence number must be at most 128 characters")
        String licenseNo,

        @Size(max = 128, message = "Position must be at most 128 characters")
        String clinicPosition) {
}
