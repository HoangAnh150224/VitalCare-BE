package com.vn.vitalcare.care.assignment.service;

import com.vn.vitalcare.care.assignment.dto.AssignmentRequests;
import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.device.service.DeviceService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Putting a patient in somebody's care and on a device, and ending either.
 *
 * <p>Both are for patients only: a neutral customer has not been taken on by
 * the clinic, so there is nobody to follow and nothing to measure. Nothing is
 * ever deleted to undo an assignment — it is ended, and the row stays as the
 * history of who followed the patient and what they wore.
 */
@Service
@Transactional(readOnly = true)
public class CareAssignmentService {

    private final MonitoringAssignmentRepository careTeams;
    private final DeviceAssignmentRepository deviceAssignments;
    private final CustomerService customerService;
    private final EmployeeService employeeService;
    private final DeviceService deviceService;
    private final Clock clock;

    public CareAssignmentService(MonitoringAssignmentRepository careTeams,
                                 DeviceAssignmentRepository deviceAssignments,
                                 CustomerService customerService,
                                 EmployeeService employeeService,
                                 DeviceService deviceService,
                                 Clock clock) {
        this.careTeams = careTeams;
        this.deviceAssignments = deviceAssignments;
        this.customerService = customerService;
        this.employeeService = employeeService;
        this.deviceService = deviceService;
        this.clock = clock;
    }

    // --- care team -----------------------------------------------------------

    public List<MonitoringAssignment> careTeam(Long customerId) {
        customerService.get(customerId);
        return careTeams.findByCustomerIdAndDeletedAtIsNullOrderByAssignedAtDesc(customerId);
    }

    @Transactional
    public MonitoringAssignment assignStaff(Long customerId, AssignmentRequests.AssignStaff request) {
        Customer patient = requirePatient(customerId);
        Employee employee = employeeService.lockForUpdate(request.employeeId());
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new FieldValidationException("employeeId", "That member of staff is no longer active");
        }
        if (careTeams.existsByCustomerIdAndEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNull(
                patient.getId(), employee.getId())) {
            throw new ConflictException("That member of staff is already following this patient");
        }

        MonitoringAssignment assignment = new MonitoringAssignment();
        assignment.start(patient, employee, CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        assignment.setNote(blankToNull(request.note()));
        return careTeams.save(assignment);
    }

    @Transactional
    public MonitoringAssignment endStaff(Long customerId, Long assignmentId, AssignmentRequests.End request) {
        MonitoringAssignment assignment = careTeams.findByIdAndCustomerIdAndDeletedAtIsNull(assignmentId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Care team assignment", assignmentId));
        if (!assignment.isActive()) {
            throw new ConflictException("This assignment has already ended");
        }
        assignment.end(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        assignment.setNote(appendNote(assignment.getNote(), request == null ? null : request.note()));
        return careTeams.save(assignment);
    }

    // --- devices -------------------------------------------------------------

    public List<DeviceAssignment> devices(Long customerId) {
        customerService.get(customerId);
        return deviceAssignments.findByCustomerIdAndDeletedAtIsNullOrderByAssignedAtDesc(customerId);
    }

    public Optional<DeviceAssignment> currentDevice(Long customerId) {
        return deviceAssignments.findFirstByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(customerId);
    }

    /**
     * Hands a device to a patient.
     *
     * <p>The device row is locked first, so of two people handing out the
     * same band at once exactly one succeeds; the partial unique indexes from
     * 016 stand behind the lock.
     */
    @Transactional
    public DeviceAssignment assignDevice(Long customerId, AssignmentRequests.AssignDevice request) {
        Customer patient = requirePatient(customerId);
        MedicalDevice device = deviceService.lockForUpdate(request.deviceId());

        if (device.getStatus() != DeviceStatus.AVAILABLE) {
            throw new ConflictException("That device is not available");
        }
        if (currentDevice(patient.getId()).isPresent()) {
            throw new ConflictException("This patient is already wearing a device; take it back first");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (request.expectedReturnAt() != null && !request.expectedReturnAt().isAfter(now)) {
            throw new FieldValidationException("expectedReturnAt", "The return date must be in the future");
        }

        DeviceAssignment assignment = new DeviceAssignment();
        assignment.start(patient, device, CurrentUser.id().orElse(null), now);
        assignment.setExpectedReturnAt(request.expectedReturnAt());
        assignment.setNote(blankToNull(request.note()));
        device.setStatus(DeviceStatus.ASSIGNED);
        return deviceAssignments.save(assignment);
    }

    /** The device came back: the assignment ends and the device is ready to hand out again. */
    @Transactional
    public DeviceAssignment returnDevice(Long customerId, Long assignmentId, AssignmentRequests.End request) {
        DeviceAssignment assignment = deviceAssignments.findByIdAndCustomerIdAndDeletedAtIsNull(assignmentId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Device assignment", assignmentId));
        if (!assignment.isActive()) {
            throw new ConflictException("This device has already been returned");
        }
        MedicalDevice device = deviceService.lockForUpdate(assignment.getDevice().getId());

        assignment.end(CurrentUser.id().orElse(null), OffsetDateTime.now(clock));
        assignment.setNote(appendNote(assignment.getNote(), request == null ? null : request.note()));
        device.setStatus(DeviceStatus.AVAILABLE);
        return deviceAssignments.save(assignment);
    }

    private Customer requirePatient(Long customerId) {
        // Locked: the "already on the team" and "already wearing a device" checks
        // that follow must not be raced by a second desk or a double click.
        Customer customer = customerService.getForUpdate(customerId);
        if (customer.getStatus() != CustomerStatus.PATIENT) {
            throw new ConflictException("Only a patient can be assigned staff or a device; activate them first");
        }
        return customer;
    }

    private static String appendNote(String existing, String addition) {
        String extra = blankToNull(addition);
        if (extra == null) {
            return existing;
        }
        return existing == null ? extra : existing + "\n" + extra;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
