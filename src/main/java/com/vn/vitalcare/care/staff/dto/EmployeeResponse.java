package com.vn.vitalcare.care.staff.dto;

import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.StaffType;
import java.time.Instant;

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
        Instant createdAt) {

    public static EmployeeResponse from(Employee employee) {
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
                employee.getCreatedAt());
    }
}
