package com.vn.vitalcare.care.assignment.dto;

import com.vn.vitalcare.entity.AssignmentStatus;
import com.vn.vitalcare.entity.DeviceAssignment;
import java.time.OffsetDateTime;

/**
 * One period of a device on a patient. Seen from the patient it names the
 * device; seen from the device it names the patient — both are filled in, so
 * one shape serves both histories.
 */
public record DeviceAssignmentResponse(
        Long id,
        AssignmentStatus status,
        OffsetDateTime assignedAt,
        OffsetDateTime expectedReturnAt,
        OffsetDateTime returnedAt,
        String note,
        DeviceSummary device,
        PatientSummary patient) {

    public record DeviceSummary(Long id, String deviceCode, String model) {
    }

    public record PatientSummary(Long id, String customerCode, String fullName) {
    }

    /** From the patient's side: the device is fetched, the patient is the one asked about. */
    public static DeviceAssignmentResponse ofPatient(DeviceAssignment assignment) {
        var device = assignment.getDevice();
        return new DeviceAssignmentResponse(
                assignment.getId(), assignment.getStatus(), assignment.getAssignedAt(),
                assignment.getExpectedReturnAt(), assignment.getUnassignedAt(), assignment.getNote(),
                new DeviceSummary(device.getId(), device.getDeviceCode(), device.getModel()),
                null);
    }

    /** From the device's side: the patient is fetched. */
    public static DeviceAssignmentResponse ofDevice(DeviceAssignment assignment) {
        var customer = assignment.getCustomer();
        return new DeviceAssignmentResponse(
                assignment.getId(), assignment.getStatus(), assignment.getAssignedAt(),
                assignment.getExpectedReturnAt(), assignment.getUnassignedAt(), assignment.getNote(),
                null,
                new PatientSummary(customer.getId(), customer.getCustomerCode(), customer.getUser().getFullName()));
    }
}
