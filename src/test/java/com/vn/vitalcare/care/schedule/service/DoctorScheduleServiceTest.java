package com.vn.vitalcare.care.schedule.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.ClinicRowLevel;
import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.schedule.dto.DoctorScheduleRequest;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleRepository;
import com.vn.vitalcare.care.schedule.service.impl.DoctorScheduleServiceImpl;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.EmployeeStatus;
import com.vn.vitalcare.entity.StaffType;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/** Whose schedule somebody may change, and which periods make no sense (SRS 3.11.7). */
class DoctorScheduleServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");

    private DoctorScheduleRepository repository;
    private EmployeeService employees;
    private AppointmentRepository appointments;
    private DoctorScheduleService service;
    private Employee doctor;
    private Employee otherDoctor;
    private Employee clinicAdmin;

    @BeforeEach
    void setUp() {
        repository = mock(DoctorScheduleRepository.class);
        when(repository.persist(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Clinic clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        doctor = staff(11L, StaffType.DOCTOR, clinic);
        otherDoctor = staff(12L, StaffType.DOCTOR, clinic);
        clinicAdmin = staff(20L, StaffType.CLINIC_ADMIN, clinic);

        employees = mock(EmployeeService.class);
        when(employees.get(12L)).thenReturn(otherDoctor);
        when(employees.get(20L)).thenReturn(clinicAdmin);

        ClinicService clinics = mock(ClinicService.class);
        when(clinics.today()).thenReturn(TODAY);

        appointments = mock(AppointmentRepository.class);
        service = new DoctorScheduleServiceImpl(
                repository, appointments, employees, clinics, new ClinicRowLevel(() -> CLINIC));
    }

    @Test
    @DisplayName("a doctor's new period is their own; they cannot add one for a colleague")
    void doctorManagesOwn() {
        when(employees.getForCurrentUser()).thenReturn(doctor);

        DoctorSchedule created = service.create(period(null, "08:00", "12:00"));
        assertEquals(doctor, created.getEmployee());
        assertEquals(CLINIC, created.getClinicId());

        assertField("employeeId", () -> service.create(period(12L, "08:00", "12:00")));
    }

    @Test
    @DisplayName("a clinic administrator names the doctor, and it has to be a doctor")
    void clinicAdminNamesTheDoctor() {
        when(employees.getForCurrentUser()).thenReturn(clinicAdmin);

        assertEquals(otherDoctor, service.create(period(12L, "08:00", "12:00")).getEmployee());
        assertField("employeeId", () -> service.create(period(null, "08:00", "12:00")));
        assertField("employeeId", () -> service.create(period(20L, "08:00", "12:00")));
    }

    @Test
    @DisplayName("a period that ends before it starts, lies in the past or overlaps another is refused")
    @SuppressWarnings("unchecked")
    void invalidPeriods() {
        when(employees.getForCurrentUser()).thenReturn(doctor);

        assertField("endTime", () -> service.create(period(null, "12:00", "08:00")));
        assertField("endTime", () -> service.create(period(null, "08:00", "08:00")));
        assertField("workDate", () -> service.create(
                new DoctorScheduleRequest(null, TODAY.minusDays(1), LocalTime.of(8, 0), LocalTime.of(12, 0), null)));

        // SRS 3.11.7: a period covering a visit somebody already registered is refused.
        when(appointments.countExpectedBetween(any(), any(), any(), any())).thenReturn(2L);
        assertThrows(ConflictException.class, () -> service.create(period(null, "08:00", "12:00")));
        when(appointments.countExpectedBetween(any(), any(), any(), any())).thenReturn(0L);

        when(repository.exists(any(Specification.class))).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.create(period(null, "08:00", "12:00")));
    }

    @Test
    @DisplayName("a colleague's period is not found for a doctor, to edit or to delete; the clinic administrator has both")
    @SuppressWarnings("unchecked")
    void doctorCannotTouchAnothers() {
        DoctorSchedule theirs = new DoctorSchedule();
        theirs.setEmployee(otherDoctor);
        theirs.setClinic(otherDoctor.getClinic());
        when(repository.findOne(any(Specification.class))).thenReturn(Optional.of(theirs));

        when(employees.getForCurrentUser()).thenReturn(doctor);
        assertThrows(ResourceNotFoundException.class, () -> service.update(5L, period(null, "13:00", "17:00")));
        assertThrows(ResourceNotFoundException.class, () -> service.delete(5L));

        when(employees.getForCurrentUser()).thenReturn(clinicAdmin);
        assertEquals(LocalTime.of(13, 0), service.update(5L, period(null, "13:00", "17:00")).getStartTime());
        service.delete(5L);
        verify(repository).remove(theirs);
    }

    private static DoctorScheduleRequest period(Long employeeId, String start, String end) {
        return new DoctorScheduleRequest(employeeId, TODAY.plusDays(1), LocalTime.parse(start), LocalTime.parse(end),
                null);
    }

    private static Employee staff(Long id, StaffType type, Clinic clinic) {
        Employee employee = mock(Employee.class);
        when(employee.getId()).thenReturn(id);
        when(employee.getStaffType()).thenReturn(type);
        when(employee.getClinic()).thenReturn(clinic);
        when(employee.getStatus()).thenReturn(EmployeeStatus.ACTIVE);
        return employee;
    }

    private static void assertField(String field, org.junit.jupiter.api.function.Executable call) {
        assertEquals(field, assertThrows(FieldValidationException.class, call).field());
    }
}
