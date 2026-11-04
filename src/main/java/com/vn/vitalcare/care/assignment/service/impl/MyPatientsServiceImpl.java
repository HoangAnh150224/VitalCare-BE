package com.vn.vitalcare.care.assignment.service.impl;

import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.assignment.service.MyPatientsService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link MyPatientsService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class MyPatientsServiceImpl implements MyPatientsService {

    private final EmployeeService employeeService;
    private final MonitoringAssignmentRepository careTeams;
    private final DeviceAssignmentRepository deviceAssignments;

    public MyPatientsServiceImpl(EmployeeService employeeService,
                                 MonitoringAssignmentRepository careTeams,
                                 DeviceAssignmentRepository deviceAssignments) {
        this.employeeService = employeeService;
        this.careTeams = careTeams;
        this.deviceAssignments = deviceAssignments;
    }

    @Override
    public List<MyPatientResponse> list() {
        return list(employeeService.getForCurrentUser());
    }

    @Override
    public MyPatientResponse get(Long customerId) {
        return get(employeeService.getForCurrentUser(), customerId);
    }

    @Override
    public List<MyPatientResponse> list(Employee me) {
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

    @Override
    public List<MyPatientResponse> listAll(Long employeeId) {
        // One query for every open assignment, grouped per patient in arrival
        // order; the patient's row starts from their first carer.
        Map<Long, List<MonitoringAssignment>> byPatient = careTeams
                .findByUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtAsc().stream()
                .collect(Collectors.groupingBy(a -> a.getCustomer().getId(), LinkedHashMap::new, Collectors.toList()));
        if (employeeId != null) {
            byPatient.values().removeIf(team -> team.stream()
                    .noneMatch(a -> a.getEmployee().getId().equals(employeeId)));
        }
        if (byPatient.isEmpty()) {
            return List.of();
        }

        Map<Long, DeviceAssignment> devices = deviceAssignments
                .findByCustomerIdInAndUnassignedAtIsNullAndDeletedAtIsNull(List.copyOf(byPatient.keySet()))
                .stream()
                .collect(Collectors.toMap(a -> a.getCustomer().getId(), Function.identity()));

        return byPatient.values().stream()
                .map(team -> MyPatientResponse.from(
                        team.getFirst(), devices.get(team.getFirst().getCustomer().getId()), team))
                .toList();
    }

    @Override
    public MyPatientResponse get(Employee me, Long customerId) {
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
