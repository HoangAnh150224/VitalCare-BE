package com.vn.vitalcare.care.assignment.dto;

import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MonitoringAssignment;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A patient as the member of staff following them sees them: who, since when
 * they have been following, and what the patient is wearing.
 *
 * <p>{@code id} is the customer's, so the clinical screens address a patient
 * the same way everywhere.
 */
public record MyPatientResponse(
        Long id,
        String customerCode,
        String fullName,
        String phone,
        LocalDate dateOfBirth,
        String gender,
        String emergencyContactName,
        String emergencyContactPhone,
        OffsetDateTime followingSince,
        DeviceAssignmentResponse.DeviceSummary device,
        List<CareTeamMemberResponse> careTeam) {

    /**
     * A patient the doctor examined but nobody assigned them to follow: the
     * same person, with no "following since" and no care team of theirs.
     */
    public static MyPatientResponse examined(Customer customer, DeviceAssignment device,
                                             List<MonitoringAssignment> careTeam) {
        return new MyPatientResponse(
                customer.getId(),
                customer.getCustomerCode(),
                customer.getFullName(),
                customer.getPhone(),
                customer.getDateOfBirth(),
                customer.getGender(),
                customer.getEmergencyContactName(),
                customer.getEmergencyContactPhone(),
                null,
                device == null ? null : new DeviceAssignmentResponse.DeviceSummary(
                        device.getDevice().getId(), device.getDevice().getDeviceCode(), device.getDevice().getModel()),
                careTeam == null ? List.of() : careTeam.stream().map(CareTeamMemberResponse::from).toList());
    }

    public static MyPatientResponse from(MonitoringAssignment mine, DeviceAssignment device,
                                         List<MonitoringAssignment> careTeam) {
        Customer customer = mine.getCustomer();
        return new MyPatientResponse(
                customer.getId(),
                customer.getCustomerCode(),
                customer.getFullName(),
                customer.getPhone(),
                customer.getDateOfBirth(),
                customer.getGender(),
                customer.getEmergencyContactName(),
                customer.getEmergencyContactPhone(),
                mine.getAssignedAt(),
                device == null ? null : new DeviceAssignmentResponse.DeviceSummary(
                        device.getDevice().getId(), device.getDevice().getDeviceCode(), device.getDevice().getModel()),
                careTeam == null ? null : careTeam.stream().map(CareTeamMemberResponse::from).toList());
    }
}
