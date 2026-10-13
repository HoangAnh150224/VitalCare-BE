package com.vn.vitalcare.care.assignment.dto;

import com.vn.vitalcare.entity.AssignmentStatus;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.entity.StaffType;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One care-team assignment with both people it names — a row of the clinic's
 * assignment list, where {@code CareTeamMemberResponse} is a row of one
 * patient's team.
 */
public record AssignmentResponse(
        Long id,
        AssignmentStatus status,
        PatientSummary patient,
        StaffSummary staff,
        OffsetDateTime assignedAt,
        /** Who made the assignment; filled on the detail only. */
        String assignedBy,
        OffsetDateTime endedAt,
        String endedBy,
        String note) {

    public record PatientSummary(Long id, String customerCode, String fullName) {
    }

    public record StaffSummary(Long id, String employeeCode, String fullName, StaffType staffType, String specialty) {
    }

    /** @param names users.id to full name, for the two actors; empty on a list, where nobody reads them */
    public static AssignmentResponse from(MonitoringAssignment assignment, Map<Long, String> names) {
        var customer = assignment.getCustomer();
        var employee = assignment.getEmployee();
        return new AssignmentResponse(
                assignment.getId(),
                assignment.getStatus(),
                new PatientSummary(customer.getId(), customer.getCustomerCode(), customer.getUser().getFullName()),
                new StaffSummary(employee.getId(), employee.getEmployeeCode(), employee.getUser().getFullName(),
                        employee.getStaffType(), employee.getSpecialty()),
                assignment.getAssignedAt(),
                name(names, assignment.getAssignedById()),
                assignment.getUnassignedAt(),
                name(names, assignment.getEndedById()),
                assignment.getNote());
    }

    // Map.of() refuses a null key, and an open assignment has nobody who ended it.
    private static String name(Map<Long, String> names, Long userId) {
        return userId == null ? null : names.get(userId);
    }
}
