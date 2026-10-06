package com.vn.vitalcare.care.assignment.service;

import static com.vn.vitalcare.care.assignment.service.CareFixtures.device;
import static com.vn.vitalcare.care.assignment.service.CareFixtures.neutral;
import static com.vn.vitalcare.care.assignment.service.CareFixtures.patient;
import static com.vn.vitalcare.care.assignment.service.CareFixtures.staff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.assignment.dto.AssignmentRequests;
import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.device.service.DeviceService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.AssignmentStatus;
import com.vn.vitalcare.entity.DeviceAssignment;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules for putting a patient in somebody's care and on a device. */
class CareAssignmentServiceTest {

    private MonitoringAssignmentRepository careTeams;
    private DeviceAssignmentRepository deviceAssignments;
    private CustomerService customers;
    private EmployeeService employees;
    private DeviceService devices;
    private CareAssignmentService service;

    @BeforeEach
    void setUp() {
        careTeams = mock(MonitoringAssignmentRepository.class);
        deviceAssignments = mock(DeviceAssignmentRepository.class);
        customers = mock(CustomerService.class);
        employees = mock(EmployeeService.class);
        devices = mock(DeviceService.class);
        when(careTeams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deviceAssignments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new CareAssignmentService(careTeams, deviceAssignments, customers, employees, devices,
                Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a patient gets a member of staff on their care team")
    void assignStaff() {
        when(customers.getForUpdate(1L)).thenReturn(patient(1));
        when(employees.lockForUpdate(5L)).thenReturn(staff(5, EmployeeStatus.ACTIVE));

        MonitoringAssignment assignment = service.assignStaff(1L, new AssignmentRequests.AssignStaff(5L, "Theo dõi tim"));

        assertEquals(AssignmentStatus.ACTIVE, assignment.getStatus());
        assertEquals(5L, assignment.getEmployee().getId());
        assertNotNull(assignment.getAssignedAt());
    }

    @Test
    @DisplayName("a neutral customer cannot be assigned anybody")
    void neutralCustomerIsRefused() {
        when(customers.getForUpdate(1L)).thenReturn(neutral(1));

        assertThrows(ConflictException.class,
                () -> service.assignStaff(1L, new AssignmentRequests.AssignStaff(5L, null)));
        assertThrows(ConflictException.class,
                () -> service.assignDevice(1L, new AssignmentRequests.AssignDevice(9L, null, null)));
        verify(careTeams, never()).save(any());
        verify(deviceAssignments, never()).save(any());
    }

    @Test
    @DisplayName("a member of staff who has left cannot be assigned")
    void inactiveStaffIsRefused() {
        when(customers.getForUpdate(1L)).thenReturn(patient(1));
        when(employees.lockForUpdate(5L)).thenReturn(staff(5, EmployeeStatus.INACTIVE));

        assertThrows(FieldValidationException.class,
                () -> service.assignStaff(1L, new AssignmentRequests.AssignStaff(5L, null)));
    }

    @Test
    @DisplayName("the same person cannot be on one patient's team twice at once")
    void duplicateStaffIsRefused() {
        when(customers.getForUpdate(1L)).thenReturn(patient(1));
        when(employees.lockForUpdate(5L)).thenReturn(staff(5, EmployeeStatus.ACTIVE));
        when(careTeams.existsByCustomerIdAndEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNull(1L, 5L)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.assignStaff(1L, new AssignmentRequests.AssignStaff(5L, null)));
    }

    @Test
    @DisplayName("ending an assignment keeps the row as history, and cannot be done twice")
    void endingKeepsHistory() {
        MonitoringAssignment assignment = new MonitoringAssignment();
        assignment.start(patient(1), staff(5, EmployeeStatus.ACTIVE), 1L, null);
        when(careTeams.findByIdAndCustomerIdAndDeletedAtIsNull(3L, 1L)).thenReturn(Optional.of(assignment));

        MonitoringAssignment ended = service.endStaff(1L, 3L, new AssignmentRequests.End("Chuyển khoa"));

        assertEquals(AssignmentStatus.ENDED, ended.getStatus());
        assertNotNull(ended.getUnassignedAt());
        assertEquals("Chuyển khoa", ended.getNote());
        assertThrows(ConflictException.class, () -> service.endStaff(1L, 3L, null));
    }

    @Test
    @DisplayName("handing out an available device puts it on the patient and marks it assigned")
    void assignDevice() {
        MedicalDevice band = device(9, DeviceStatus.AVAILABLE);
        when(customers.getForUpdate(1L)).thenReturn(patient(1));
        when(devices.lockForUpdate(9L)).thenReturn(band);

        DeviceAssignment assignment = service.assignDevice(1L, new AssignmentRequests.AssignDevice(9L, null, null));

        assertEquals(AssignmentStatus.ACTIVE, assignment.getStatus());
        assertEquals(DeviceStatus.ASSIGNED, band.getStatus());
    }

    @Test
    @DisplayName("a device that is not on the shelf cannot be handed out")
    void unavailableDeviceIsRefused() {
        when(customers.getForUpdate(1L)).thenReturn(patient(1));
        when(devices.lockForUpdate(9L)).thenReturn(device(9, DeviceStatus.MAINTENANCE));

        assertThrows(ConflictException.class,
                () -> service.assignDevice(1L, new AssignmentRequests.AssignDevice(9L, null, null)));
    }

    @Test
    @DisplayName("a patient already wearing a device cannot be given a second one")
    void secondDeviceIsRefused() {
        MedicalDevice band = device(9, DeviceStatus.AVAILABLE);
        when(customers.getForUpdate(1L)).thenReturn(patient(1));
        when(devices.lockForUpdate(9L)).thenReturn(band);
        when(deviceAssignments.findFirstByCustomerIdAndUnassignedAtIsNullAndDeletedAtIsNull(1L))
                .thenReturn(Optional.of(new DeviceAssignment()));

        assertThrows(ConflictException.class,
                () -> service.assignDevice(1L, new AssignmentRequests.AssignDevice(9L, null, null)));
        assertEquals(DeviceStatus.AVAILABLE, band.getStatus());
    }

    @Test
    @DisplayName("taking a device back ends the assignment and puts the device back on the shelf")
    void returnDevice() {
        MedicalDevice band = device(9, DeviceStatus.ASSIGNED);
        DeviceAssignment assignment = new DeviceAssignment();
        assignment.start(patient(1), band, 1L, null);
        when(deviceAssignments.findByIdAndCustomerIdAndDeletedAtIsNull(4L, 1L)).thenReturn(Optional.of(assignment));
        when(devices.lockForUpdate(9L)).thenReturn(band);

        DeviceAssignment returned = service.returnDevice(1L, 4L, null);

        assertEquals(AssignmentStatus.ENDED, returned.getStatus());
        assertNotNull(returned.getUnassignedAt());
        assertEquals(DeviceStatus.AVAILABLE, band.getStatus());
        assertThrows(ConflictException.class, () -> service.returnDevice(1L, 4L, null));
    }
}
