package com.vn.vitalcare.care.assignment.service;

import com.vn.vitalcare.care.assignment.dto.AssignmentRequests;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.MonitoringAssignment;
import java.util.List;
import java.util.Optional;

/**
 * Putting a patient in somebody's care and on a device, and ending either.
 *
 * <p>Both are for patients only: a neutral customer has not been taken on by
 * the clinic, so there is nobody to follow and nothing to measure. Nothing is
 * ever deleted to undo an assignment — it is ended, and the row stays as the
 * history of who followed the patient and what they wore.
 */
public interface CareAssignmentService {

    List<MonitoringAssignment> careTeam(Long customerId);

    MonitoringAssignment assignStaff(Long customerId, AssignmentRequests.AssignStaff request);

    MonitoringAssignment endStaff(Long customerId, Long assignmentId, AssignmentRequests.End request);

    List<DeviceAssignment> devices(Long customerId);

    Optional<DeviceAssignment> currentDevice(Long customerId);

    /**
     * Hands a device to a patient.
     *
     * <p>The device row is locked first, so of two people handing out the
     * same band at once exactly one succeeds; the partial unique indexes from
     * 016 stand behind the lock.
     */
    DeviceAssignment assignDevice(Long customerId, AssignmentRequests.AssignDevice request);

    /** The device came back: the assignment ends and the device is ready to hand out again. */
    DeviceAssignment returnDevice(Long customerId, Long assignmentId, AssignmentRequests.End request);
}
