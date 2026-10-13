package com.vn.vitalcare.care.staff.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A member of staff, with the name and number their account holds. */
public record EmployeeResponse(
        Long id,
        String employeeCode,
        Long userId,
        String fullName,
        String phone,
        StaffType staffType,
        String specialty,
        String professionalTitle,
        String licenseNo,
        String clinicPosition,
        EmployeeStatus status,
        UUID clinicId,
        Instant createdAt,
        /**
         * What the caller may do to this row, from the row-level write and
         * delete policies — the framework's {@code _can}, read by the client's
         * access control to hide buttons that would end in a 404 or 422. Empty
         * when there is no scope to report against.
         */
        @JsonProperty("_can")
        Map<String, Boolean> can) {

    /** Without row flags — for a caller with no scope to report against. */
    public static EmployeeResponse from(Employee employee) {
        return from(employee, null);
    }

    public static EmployeeResponse from(Employee employee, RowLevelSecurity rowLevel) {
        var user = employee.getUser();
        return new EmployeeResponse(
                employee.getId(),
                employee.getEmployeeCode(),
                user.getId(),
                user.getFullName(),
                user.getPhone(),
                employee.getStaffType(),
                employee.getSpecialty(),
                employee.getProfessionalTitle(),
                employee.getLicenseNo(),
                employee.getClinicPosition(),
                employee.getStatus(),
                // The id only: it reads off the proxy without loading the clinic.
                employee.getClinic() == null ? null : employee.getClinic().getClinicId(),
                employee.getCreatedAt(),
                rowLevel == null ? Map.of() : rowLevel.capabilities(employee));
    }
}
