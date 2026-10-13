package com.vn.vitalcare.care.assignment.service;

import static com.vn.vitalcare.care.assignment.service.CareFixtures.patient;
import static com.vn.vitalcare.care.assignment.service.CareFixtures.staff;
import static com.vn.vitalcare.care.assignment.service.CareFixtures.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.ClinicRowLevel;
import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.care.assignment.repository.DeviceAssignmentRepository;
import com.vn.vitalcare.care.assignment.repository.MonitoringAssignmentRepository;
import com.vn.vitalcare.care.assignment.service.impl.MyPatientsServiceImpl;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.staff.dto.EmployeeCreateRequest;
import com.vn.vitalcare.care.staff.dto.EmployeePatchRequest;
import com.vn.vitalcare.care.staff.repository.EmployeeRepository;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.care.staff.service.impl.EmployeeServiceImpl;
import com.vn.vitalcare.entity.AssignmentStatus;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.MonitoringAssignment;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/** Creating and retiring staff, and the "my patients" view that is pinned to the caller. */
class StaffAndPatientsServiceTest {

    private EmployeeRepository employeeRepository;
    private MonitoringAssignmentRepository careTeams;
    private DeviceAssignmentRepository deviceAssignments;
    private UserService userService;
    private EmployeeService employees;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        careTeams = mock(MonitoringAssignmentRepository.class);
        deviceAssignments = mock(DeviceAssignmentRepository.class);
        userService = mock(UserService.class);
        ClinicService clinics = mock(ClinicService.class);
        when(clinics.listAll()).thenReturn(List.of());
        when(employeeRepository.persist(any())).thenAnswer(invocation -> invocation.getArgument(0));

        employees = new EmployeeServiceImpl(employeeRepository, careTeams, userService, clinics, Optional::empty,
                ClinicRowLevel.unrestricted(), event -> { }, Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a new member of staff gets the role their kind of staff holds, and a code")
    void createStaff() {
        when(userService.createStaffAccount(any(), any(), any(), any())).thenReturn(user(50, "+84900000050", "BS. A"));
        when(employeeRepository.persist(any())).thenAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            ReflectionTestUtils.setField(employee, "id", 7L);
            return employee;
        });

        Employee created = employees.create(new EmployeeCreateRequest(
                "0900000050", "BS. A", "Passw0rd!", StaffType.NURSE, null, null, null, null, null));

        verify(userService).createStaffAccount("0900000050", "Passw0rd!", "BS. A", "NURSE");
        assertEquals("NV000007", created.getEmployeeCode());
        assertEquals(EmployeeStatus.ACTIVE, created.getStatus());
    }

    @Test
    @DisplayName("a clinic administrator is created as staff and gets the CLINIC_ADMIN role")
    void createClinicAdmin() {
        when(userService.createStaffAccount(any(), any(), any(), any()))
                .thenReturn(user(52, "+84900000052", "QTPK. C"));
        when(employeeRepository.persist(any())).thenAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            ReflectionTestUtils.setField(employee, "id", 8L);
            return employee;
        });

        Employee created = employees.create(new EmployeeCreateRequest(
                "0900000052", "QTPK. C", "Passw0rd!", StaffType.from("clinic_admin"), null, null, null, null, null));

        verify(userService).createStaffAccount("0900000052", "Passw0rd!", "QTPK. C", "CLINIC_ADMIN");
        assertEquals(StaffType.CLINIC_ADMIN, created.getStaffType());
    }

    @Test
    @DisplayName("staff cannot be added to a clinic outside the creator's scope: the saved row is refused")
    void creatingAtAnotherClinicIsRefused() {
        UUID own = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
        UUID other = UUID.fromString("c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48");
        Clinic otherClinic = new Clinic();
        otherClinic.setClinicId(other);
        ClinicService clinics = mock(ClinicService.class);
        when(clinics.get(other)).thenReturn(otherClinic);
        EmployeeService scoped = new EmployeeServiceImpl(employeeRepository, careTeams, userService, clinics,
                () -> Optional.of(own), new ClinicRowLevel(() -> own), event -> { },
                Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneOffset.UTC));

        when(userService.createStaffAccount(any(), any(), any(), any())).thenReturn(user(51, "+84900000051", "BS. B"));

        assertThrows(RowLevelViolationException.class, () -> scoped.create(new EmployeeCreateRequest(
                "0900000051", "BS. B", "Passw0rd!", StaffType.DOCTOR, null, null, null, null, other)));
        // Checked against the flushed row, as the framework does; the exception
        // rolls the account and the staff record back together.
        verify(employeeRepository).flush();
    }

    @Test
    @DisplayName("somebody who leaves comes off every care team, and their account stops signing in")
    void leavingEndsAssignments() {
        Employee leaving = staff(5, EmployeeStatus.ACTIVE);
        MonitoringAssignment first = assignment(patient(1), leaving);
        MonitoringAssignment second = assignment(patient(2), leaving);
        when(employeeRepository.findForUpdate(5L)).thenReturn(Optional.of(leaving));
        when(employeeRepository.exists(any(Specification.class))).thenReturn(true);
        when(careTeams.findByEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtDesc(5L))
                .thenReturn(List.of(first, second));

        employees.update(5L, new EmployeePatchRequest(null, null, null, null, EmployeeStatus.INACTIVE));

        assertEquals(EmployeeStatus.INACTIVE, leaving.getStatus());
        assertEquals(AssignmentStatus.ENDED, first.getStatus());
        assertEquals(AssignmentStatus.ENDED, second.getStatus());
        verify(userService).setSignInAllowed(eq(205L), eq(false));
    }

    @Test
    @DisplayName("'my patients' is the caller's own caseload, and somebody else's patient is not found")
    void myPatientsIsPinnedToTheCaller() {
        EmployeeService current = mock(EmployeeService.class);
        Employee me = staff(5, EmployeeStatus.ACTIVE);
        when(current.getForCurrentUser()).thenReturn(me);
        when(careTeams.findByEmployeeIdAndUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtDesc(5L))
                .thenReturn(List.of(assignment(patient(1), me)));
        MyPatientsService myPatients = new MyPatientsServiceImpl(current, careTeams, deviceAssignments);

        List<MyPatientResponse> list = myPatients.list();
        assertEquals(1, list.size());
        assertEquals(1L, list.getFirst().id());

        assertEquals(1L, myPatients.get(1L).id());
        assertThrows(ResourceNotFoundException.class, () -> myPatients.get(2L));
    }

    @Test
    @DisplayName("every caseload at once: one row per patient with their whole team, narrowed by a member of staff")
    void allPatientsGroupsByPatient() {
        Employee doctor = staff(5, EmployeeStatus.ACTIVE);
        Employee nurse = staff(6, EmployeeStatus.ACTIVE);
        var first = patient(1);
        var second = patient(2);
        when(careTeams.findByUnassignedAtIsNullAndDeletedAtIsNullOrderByAssignedAtAsc()).thenReturn(List.of(
                assignment(first, doctor), assignment(first, nurse), assignment(second, nurse)));
        MyPatientsService myPatients =
                new MyPatientsServiceImpl(mock(EmployeeService.class), careTeams, deviceAssignments);

        List<MyPatientResponse> all = myPatients.listAll(null);
        assertEquals(List.of(1L, 2L), all.stream().map(MyPatientResponse::id).toList());
        assertEquals(2, all.getFirst().careTeam().size());

        List<MyPatientResponse> doctors = myPatients.listAll(5L);
        assertEquals(List.of(1L), doctors.stream().map(MyPatientResponse::id).toList());
        // Narrowed to the doctor's patients, but each still shows the whole team.
        assertEquals(2, doctors.getFirst().careTeam().size());
    }

    private static MonitoringAssignment assignment(com.vn.vitalcare.entity.Customer customer, Employee employee) {
        MonitoringAssignment assignment = new MonitoringAssignment();
        assignment.start(customer, employee, 1L, null);
        return assignment;
    }
}
