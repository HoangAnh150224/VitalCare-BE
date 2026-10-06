package com.vn.vitalcare.care.assignment.service;

import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "My patients": the patients the signed-in member of staff is following now.
 *
 * <p>Every answer starts from the caller's own staff record and their open
 * assignments, never from anything in the request — as {@code my_appointments}
 * does for customers. A patient they do not follow answers as not found.
 */
@Service
@Transactional(readOnly = true)
public class MyPatientsService {

    private final EmployeeService employeeService;
    private final MonitoringAssignmentRepository careTeams;
    private final DeviceAssignmentRepository deviceAssignments;

    public MyPatientsService(EmployeeService employeeService,
                             MonitoringAssignmentRepository careTeams,
                             DeviceAssignmentRepository deviceAssignments) {
        this.employeeService = employeeService;
        this.careTeams = careTeams;
        this.deviceAssignments = deviceAssignments;
    }

    public List<MyPatientResponse> list() {
        Employee me = employeeService.getForCurrentUser();
        List<MonitoringAssignment> mine =
                careTeams.findByEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtDesc(me.getId());
        if (mine.isEmpty()) {
            return List.of();
        }

        Map<Long, DeviceAssignment> devices = deviceAssignments
                .findByCustomerIdInAndUnassignedAtIsNullAndDeletedAtIsNull(
                        mine.stream().map(a -> a.getCustomer().getId()).toList())
                .stream()
                .collect(Collectors.toMap(a -> a.getCustomer().getId(), Function.identity()));

        return mine.stream()
                .map(assignment -> MyPatientResponse.from(
                        assignment, devices.get(assignment.getCustomer().getId()), null))
                .toList();
    }

    public MyPatientResponse get(Long customerId) {
        Employee me = employeeService.getForCurrentUser();
        MonitoringAssignment mine = careTeams
                .findByEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtDesc(me.getId())
                .stream()
                .filter(assignment -> assignment.getCustomer().getId().equals(customerId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Patient", customerId));

        DeviceAssignment device = deviceAssignments
                .findFirstByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(customerId)
                .orElse(null);
        List<MonitoringAssignment> team =
                careTeams.findByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(customerId);
        return MyPatientResponse.from(mine, device, team);
    }
}
