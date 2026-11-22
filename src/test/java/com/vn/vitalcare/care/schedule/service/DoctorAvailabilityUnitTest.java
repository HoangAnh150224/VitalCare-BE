package com.vn.vitalcare.care.schedule.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.examroom.repository.ExamRoomRepository;
import com.vn.vitalcare.care.schedule.dto.DayAvailability;
import com.vn.vitalcare.care.schedule.dto.WorkingHoursEntry;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleRepository;
import com.vn.vitalcare.care.schedule.repository.DoctorWorkingHoursRepository;
import com.vn.vitalcare.care.schedule.service.impl.DoctorAvailabilityServiceImpl;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.DoctorWorkingHours;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/** FE-10 Doctor Schedule / Manage Doctor Availability: DoctorAvailabilityServiceImpl. Report-5.1, sheet BE_Gaps. */
class DoctorAvailabilityUnitTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
    /** Monday 23/11/2026. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);
    private DoctorWorkingHoursRepository hours;
    private DoctorScheduleRepository blocked;
    private AppointmentRepository appointments;
    private EmployeeService employees;
    private ClinicService clinics;
    private ClinicScope scope;
    private ExamRoomRepository rooms;
    private Employee doctor;

    private DoctorAvailabilityServiceImpl service(String nowInVietnam) {
        Instant now = java.time.LocalDateTime.parse(nowInVietnam).atZone(VN).toInstant();
        return new DoctorAvailabilityServiceImpl(hours, blocked, appointments, employees, clinics, scope, rooms,
                Clock.fixed(now, VN));
    }

    @BeforeEach
    void setUp() {
        hours = mock(DoctorWorkingHoursRepository.class);
        blocked = mock(DoctorScheduleRepository.class);
        appointments = mock(AppointmentRepository.class);
        employees = mock(EmployeeService.class);
        clinics = mock(ClinicService.class);
        scope = mock(ClinicScope.class);
        rooms = mock(ExamRoomRepository.class);
        when(clinics.timeZone()).thenReturn(VN);
        when(clinics.today()).thenReturn(MONDAY.minusDays(1));
        when(clinics.isWithinBookingWindow(any())).thenReturn(true);
        doctor = new Employee();
        ReflectionTestUtils.setField(doctor, "id", 5L);
        Clinic clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        doctor.setClinic(clinic);
        when(blocked.findAll(any(Specification.class))).thenReturn(List.of());
    }

    private void morning(int visitMinutes) {
        when(hours.findDay(5L, (short) 1)).thenReturn(List.of(
                new DoctorWorkingHours(doctor, DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0), visitMinutes)));
    }

    private static WorkingHoursEntry entry(int day, String start, String end, Long room) {
        return new WorkingHoursEntry(day, LocalTime.parse(start), LocalTime.parse(end), 15, room);
    }

    @Test void da01_aSessionIsCutIntoTurns_aPartialLastTurnIsDropped() {
        morning(25);
        List<Turn> turns = service("2026-11-20T07:00").turns(doctor, MONDAY);
        assertEquals(List.of(new Turn(LocalTime.of(8, 0), LocalTime.of(8, 25)), new Turn(LocalTime.of(8, 25), LocalTime.of(8, 50))), turns);
    }

    @Test void da02_blockedTimeRemovesTheTurnsItTouches_noSessionNoTurns() {
        morning(15);
        DoctorSchedule off = new DoctorSchedule();
        off.setStartTime(LocalTime.of(8, 10));
        off.setEndTime(LocalTime.of(8, 20));
        when(blocked.findAll(any(Specification.class))).thenReturn(List.of(off));
        List<Turn> turns = service("2026-11-20T07:00").turns(doctor, MONDAY);
        assertEquals(List.of(LocalTime.of(8, 30), LocalTime.of(8, 45)), turns.stream().map(Turn::start).toList());
        assertEquals(List.of(), service("2026-11-20T07:00").turns(doctor, MONDAY.plusDays(1)));
    }

    @Test void da03_aFutureDay_nextNumberGetsItsTurnInOrder() {
        morning(15);
        when(appointments.countWaiting(5L, MONDAY)).thenReturn(1L);
        when(appointments.maxQueueNumber(5L, MONDAY)).thenReturn(1);
        DayAvailability day = service("2026-11-20T07:00").day(doctor, MONDAY);
        assertTrue(day.working());
        assertEquals(4, day.capacity());
        assertEquals(3, day.remaining());
        assertEquals(2, day.nextNumber());
        assertEquals(LocalTime.of(8, 15), day.estimatedStart());
        assertTrue(day.bookable());
    }

    @Test void da04_today_turnsAlreadyStartedDoNotCount() {
        morning(15);
        when(clinics.today()).thenReturn(MONDAY);
        DayAvailability day = service("2026-11-23T08:20").day(doctor, MONDAY);
        assertEquals(2, day.remaining());
        assertEquals(LocalTime.of(8, 30), day.estimatedStart());
    }

    @Test void da05_aFullDayOrAPastDayIsNotBookable() {
        morning(15);
        when(appointments.countWaiting(5L, MONDAY)).thenReturn(4L);
        DayAvailability full = service("2026-11-20T07:00").day(doctor, MONDAY);
        assertEquals(0, full.remaining());
        assertFalse(full.bookable());
        assertNull(full.nextNumber());
        when(appointments.countWaiting(5L, MONDAY)).thenReturn(0L);
        when(clinics.today()).thenReturn(MONDAY.plusDays(1));
        assertFalse(service("2026-11-20T07:00").day(doctor, MONDAY).bookable());
    }

    @Test void da06_weekOfADoctorOfAnotherClinicIsNotFound() {
        when(employees.doctor(5L)).thenReturn(doctor);
        when(scope.currentClinicId()).thenReturn(Optional.of(UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class, () -> service("2026-11-20T07:00").week(5L));
        when(scope.currentClinicId()).thenReturn(Optional.of(CLINIC));
        when(hours.findWeek(5L)).thenReturn(List.of());
        assertEquals(List.of(), service("2026-11-20T07:00").week(5L));
    }

    @Test void da07_replaceWeekRefusesASessionEndingBeforeItStarts_andOverlaps() {
        DoctorAvailabilityServiceImpl s = service("2026-11-20T07:00");
        assertEquals("A session must end after it starts", assertThrows(FieldValidationException.class,
                () -> s.replaceWeek(doctor, List.of(entry(1, "09:00", "08:00", null)))).getMessage());
        assertEquals("Two sessions on the same day overlap", assertThrows(FieldValidationException.class,
                () -> s.replaceWeek(doctor, List.of(entry(1, "08:00", "10:00", null), entry(1, "09:30", "11:00", null)))).getMessage());
        verify(hours, never()).deleteWeek(anyLong());
    }

    @Test void da08_replaceWeekStoresTheSessionsInOrder() {
        when(hours.save(any(DoctorWorkingHours.class))).thenAnswer(i -> i.getArgument(0));
        List<DoctorWorkingHours> saved = service("2026-11-20T07:00").replaceWeek(doctor,
                List.of(entry(3, "13:00", "17:00", null), entry(1, "08:00", "12:00", null)));
        assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), saved.stream().map(DoctorWorkingHours::getDayOfWeek).toList());
        verify(employees).lockDoctor(5L);
        verify(hours).deleteWeek(5L);
    }

    @Test void da09_aRoomOfAnotherClinicOrTakenByAColleagueIsRefused() {
        ExamRoom elsewhere = new ExamRoom();
        Clinic other = new Clinic();
        other.setClinicId(UUID.randomUUID());
        elsewhere.setClinic(other);
        when(rooms.findLive(9L)).thenReturn(Optional.of(elsewhere));
        assertEquals("Pick one of the clinic's rooms", assertThrows(FieldValidationException.class,
                () -> service("2026-11-20T07:00").replaceWeek(doctor, List.of(entry(1, "08:00", "12:00", 9L)))).getMessage());

        ExamRoom mine = new ExamRoom();
        ReflectionTestUtils.setField(mine, "id", 10L);
        Clinic clinic = new Clinic();
        clinic.setClinicId(CLINIC);
        mine.setClinic(clinic);
        mine.setCode("P101");
        when(rooms.findLive(10L)).thenReturn(Optional.of(mine));
        Employee colleague = new Employee();
        User account = mock(User.class);
        when(account.getFullName()).thenReturn("BS. Bình");
        colleague.setUser(account);
        DoctorWorkingHours clash = new DoctorWorkingHours(colleague, DayOfWeek.MONDAY, LocalTime.of(7, 0), LocalTime.of(9, 0), 15);
        when(hours.findClashes(eq(10L), anyShort(), any(), any(), eq(5L))).thenReturn(List.of(clash));
        assertEquals("Room P101 is taken by BS. Bình from 07:00 to 09:00 on that day", assertThrows(FieldValidationException.class,
                () -> service("2026-11-20T07:00").replaceWeek(doctor, List.of(entry(1, "08:00", "12:00", 10L)))).getMessage());
    }
}
