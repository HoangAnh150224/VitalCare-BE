package com.vn.vitalcare.care.assignment.dto;

import com.vn.vitalcare.entity.AssignmentStatus;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.entity.StaffType;
import java.time.OffsetDateTime;

/** One person's time on a patient's care team. */
public record CareTeamMemberResponse(
        Long id,
        AssignmentStatus status,
        OffsetDateTime assignedAt,
        OffsetDateTime endedAt,
        String note,
        Long employeeId,
        String employeeCode,
        String fullName,
        StaffType staffType,
        String specialty) {

    public static CareTeamMemberResponse from(MonitoringAssignment assignment) {
        var employee = assignment.getEmployee();
        return new CareTeamMemberResponse(
                assignment.getId(),
                assignment.getStatus(),
                assignment.getAssignedAt(),
                assignment.getUnassignedAt(),
                assignment.getNote(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getUser().getFullName(),
                employee.getStaffType(),
                employee.getSpecialty());
    }
}
