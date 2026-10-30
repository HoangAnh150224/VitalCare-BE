package com.vn.vitalcare.care.examroom.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

import com.vn.vitalcare.care.ClinicRowLevel;
import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.examroom.repository.ExamRoomRepository;
import com.vn.vitalcare.care.examroom.service.impl.ExamRoomServiceImpl;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleRepository;
import com.vn.vitalcare.care.schedule.repository.DoctorWorkingHoursRepository;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.AppointmentStatus;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.DoctorWorkingHours;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.entity.ExamRoomStatus;
import com.vn.vitalcare.share.exception.ConflictException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * What putting people in rooms guarantees: only a free room with a doctor on
 * duty takes anybody, it takes the doctor's next person, and every way of
 * leaving a room frees it for the next.
 */
class ExamRoomDispatchTest {

    private static final UUID CLINIC = UUID.fromString("5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19");
    // Tuesday 20 October 2026, 09:00 in Hanoi.
    private static final Instant NOW = Instant.parse("2026-10-20T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private ExamRoomRepository rooms;
    private AppointmentRepository appointments;
    private DoctorWorkingHoursRepository hours;
    private ApplicationEventPublisher events;
    private RoomDispatcher dispatcher;
    private ExamRoomService service;

    private ExamRoom room;
    private Employee doctor;

    @BeforeEach
    void setUp() {
        rooms = mock(ExamRoomRepository.class);
        appointments = mock(AppointmentRepository.class);
        hours = mock(DoctorWorkingHoursRepository.class);
        events = mock(ApplicationEventPublisher.class);
        ClinicService clinicService = mock(ClinicService.class);
        when(clinicService.timeZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        dispatcher = new RoomDispatcher(rooms, appointments, hours, mock(DoctorScheduleRepository.class),
                clinicService, events, mock(EntityManager.class), Duration.ofMinutes(3), clock);
        service = new ExamRoomServiceImpl(rooms, appointments, dispatcher, clinicService, () -> Optional.of(CLINIC),
                new ClinicRowLevel(() -> CLINIC), events, clock, mock(com.vn.vitalcare.care.appointment.service.VisitReturns.class));

        Clinic clinic = new Clinic();
        ReflectionTestUtils.setField(clinic, "clinicId", CLINIC);
        room = new ExamRoom();
        ReflectionTestUtils.setField(room, "id", 7L);
        room.setClinic(clinic);
        room.setCode("P101");
        when(rooms.findForUpdate(7L)).thenReturn(Optional.of(room));
        when(rooms.findOne(any(Specification.class))).thenReturn(Optional.of(room));
        when(appointments.persist(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rooms.persist(any())).thenAnswer(invocation -> invocation.getArgument(0));

        doctor = new Employee();
        ReflectionTestUtils.setField(doctor, "id", 11L);
    }

    @Test
    @DisplayName("a free room with a doctor on duty takes the doctor's next person")
    void dispatchFillsAFreeRoom() {
        onDuty();
        Appointment next = checkedIn(101L, 3);
        when(appointments.lockNextInQueue(eq(11L), eq(TODAY), any())).thenReturn(Optional.of(next));

        Optional<Appointment> called = dispatcher.dispatch(7L);

        assertSame(next, called.orElseThrow());
        assertEquals(AppointmentStatus.IN_EXAM, next.getStatus());
        assertSame(room, next.getExamRoom());
        assertNotNull(next.getCalledAt());
        assertEquals(ExamRoomStatus.OCCUPIED, room.getStatus());
        assertSame(next, room.getCurrentAppointment());
        verify(events).publishEvent(new ExamRoomEvents.BoardChanged(CLINIC));
    }

    @Test
    @DisplayName("an occupied or paused room takes nobody, and the queue is not even read")
    void dispatchLeavesABusyRoomAlone() {
        onDuty();
        room.occupy(checkedIn(100L, 1));
        assertTrue(dispatcher.dispatch(7L).isEmpty());

        room.release();
        room.pause();
        assertTrue(dispatcher.dispatch(7L).isEmpty());

        verify(appointments, never()).lockNextInQueue(anyLong(), any(), any());
    }

    @Test
    @DisplayName("a room with no doctor this session, or a doctor with nobody waiting, stays free")
    void dispatchNeedsADoctorAndSomebodyWaiting() {
        when(hours.findInRoomAt(eq(7L), anyShort(), any())).thenReturn(List.of());
        assertTrue(dispatcher.dispatch(7L).isEmpty());

        onDuty();
        when(appointments.lockNextInQueue(eq(11L), eq(TODAY), any())).thenReturn(Optional.empty());
        assertTrue(dispatcher.dispatch(7L).isEmpty());
        assertEquals(ExamRoomStatus.AVAILABLE, room.getStatus());
    }

    @Test
    @DisplayName("finishing completes the visit and frees the room for the next")
    void finishFreesTheRoom() {
        Appointment inside = inRoom(100L);

        service.finish(7L);

        assertEquals(AppointmentStatus.COMPLETED, inside.getStatus());
        assertSame(room, inside.getExamRoom(), "the room stays on the visit as a record");
        assertEquals(ExamRoomStatus.AVAILABLE, room.getStatus());
        assertNull(room.getCurrentAppointment());
        verify(events).publishEvent(new ExamRoomEvents.RoomFreed(CLINIC, 7L));
    }

    @Test
    @DisplayName("passing somebody over sends them back to the queue, marked, and frees the room")
    void passOverRequeues() {
        Appointment inside = inRoom(100L);

        service.passOver(7L);

        assertEquals(AppointmentStatus.CHECKED_IN, inside.getStatus());
        assertNull(inside.getExamRoom());
        assertNotNull(inside.getSkippedAt());
        assertEquals(ExamRoomStatus.AVAILABLE, room.getStatus());
        verify(events).publishEvent(new ExamRoomEvents.RoomFreed(CLINIC, 7L));
    }

    @Test
    @DisplayName("an empty room cannot be finished or passed over")
    void emptyRoomHasNobodyToFinish() {
        assertThrows(ConflictException.class, () -> service.finish(7L));
        assertThrows(ConflictException.class, () -> service.passOver(7L));
    }

    @Test
    @DisplayName("calling next on a paused or occupied room is refused, saying why")
    void callNextExplainsRefusals() {
        room.pause();
        assertThrows(ConflictException.class, () -> service.callNext(7L));
        room.resume();
        room.occupy(checkedIn(100L, 1));
        assertThrows(ConflictException.class, () -> service.callNext(7L));
    }

    @Test
    @DisplayName("reopening a paused room with somebody still inside leaves it occupied, not free")
    void resumeKeepsTheOccupant() {
        inRoom(100L);
        room.pause();

        service.resume(7L);

        assertEquals(ExamRoomStatus.OCCUPIED, room.getStatus());
        verify(events, never()).publishEvent(new ExamRoomEvents.RoomFreed(CLINIC, 7L));
    }

    @Test
    @DisplayName("ending a visit elsewhere frees its room only if it is still the one inside")
    void releaseForMatchesTheOccupant() {
        Appointment inside = inRoom(100L);
        Appointment earlier = checkedIn(99L, 1);
        earlier.callInto(room, null);

        service.releaseFor(earlier);
        assertSame(inside, room.getCurrentAppointment());

        service.releaseFor(inside);
        assertNull(room.getCurrentAppointment());
        assertEquals(ExamRoomStatus.AVAILABLE, room.getStatus());
    }

    @Test
    @DisplayName("a patient called in who never came is a no-show, and the room is free again")
    void noShowFreesTheRoom() {
        Appointment inside = inRoom(100L);

        service.noShow(7L);

        assertEquals(AppointmentStatus.NO_SHOW, inside.getStatus());
        assertEquals(ExamRoomStatus.AVAILABLE, room.getStatus());
        verify(events).publishEvent(new ExamRoomEvents.RoomFreed(CLINIC, 7L));
    }

    @Test
    @DisplayName("a visit left in a room from yesterday is closed by the sweep; today's is left alone")
    void staleVisitIsClosed() {
        Appointment today = inRoom(100L);
        assertTrue(!dispatcher.closeStale(7L));
        assertSame(today, room.getCurrentAppointment());

        today.setAppointmentDate(TODAY.minusDays(1));
        assertTrue(dispatcher.closeStale(7L));
        assertEquals(AppointmentStatus.COMPLETED, today.getStatus());
        assertNull(room.getCurrentAppointment());
        assertEquals(ExamRoomStatus.AVAILABLE, room.getStatus());
    }

    private void onDuty() {
        DoctorWorkingHours session = new DoctorWorkingHours(doctor, DayOfWeek.TUESDAY, LocalTime.of(8, 0),
                LocalTime.of(12, 0), 30, room);
        when(hours.findInRoomAt(eq(7L), anyShort(), any())).thenReturn(List.of(session));
    }

    private Appointment inRoom(Long id) {
        Appointment appointment = checkedIn(id, 1);
        appointment.callInto(room, null);
        room.occupy(appointment);
        return appointment;
    }

    private Appointment checkedIn(Long id, int queueNumber) {
        Appointment appointment = new Appointment();
        ReflectionTestUtils.setField(appointment, "id", id);
        appointment.setCustomer(new Customer());
        appointment.setEmployee(doctor);
        appointment.setAppointmentDate(TODAY);
        appointment.setQueueNumber(queueNumber);
        appointment.checkIn(null, null);
        return appointment;
    }
}
