package com.vn.vitalcare.care.examroom.service;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.examroom.repository.ExamRoomRepository;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleRepository;
import com.vn.vitalcare.care.schedule.repository.DoctorScheduleSpecifications;
import com.vn.vitalcare.care.schedule.repository.DoctorWorkingHoursRepository;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.DoctorWorkingHours;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.entity.ExamRoomStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a patient is put in a room.
 *
 * <p>Safe to call from any number of threads, and from any number of
 * application nodes, for the same room or different ones:
 * <ol>
 *   <li>the room row is locked first, so two calls for one room run one after
 *       the other, and the second finds it occupied and does nothing;</li>
 *   <li>the next patient is read {@code FOR NO KEY UPDATE SKIP LOCKED}, so a call for
 *       another room never waits on, or takes, a row already being placed;</li>
 *   <li>and whatever the code does, the unique index on a room's
 *       {@code IN_EXAM} visit refuses a second patient in it at commit.</li>
 * </ol>
 * Manual calls ("call next"), the events that follow a check-in or a room
 * freeing up, and the periodic sweep all come here; nothing else assigns.
 */
@Component
public class RoomDispatcher {

    private final ExamRoomRepository rooms;
    private final AppointmentRepository appointments;
    private final DoctorWorkingHoursRepository hours;
    private final DoctorScheduleRepository blocked;
    private final ClinicService clinicService;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    /** How long somebody passed over waits before a room calls them again on its own. */
    private final Duration recallAfter;
    private final Clock clock;

    public RoomDispatcher(ExamRoomRepository rooms,
                          AppointmentRepository appointments,
                          DoctorWorkingHoursRepository hours,
                          DoctorScheduleRepository blocked,
                          ClinicService clinicService,
                          ApplicationEventPublisher events,
                          EntityManager entityManager,
                          @Value("${app.dispatch.recall-after:3m}") Duration recallAfter,
                          Clock clock) {
        this.rooms = rooms;
        this.appointments = appointments;
        this.hours = hours;
        this.blocked = blocked;
        this.clinicService = clinicService;
        this.events = events;
        this.entityManager = entityManager;
        this.recallAfter = recallAfter;
        this.clock = clock;
    }

    /**
     * Calls the next patient into the room, if it is free, has a doctor this
     * session, and the doctor has somebody waiting.
     *
     * @return who was called in; empty when nothing changed
     */
    @Transactional
    public Optional<Appointment> dispatch(Long roomId) {
        Optional<ExamRoom> locked = lock(roomId);
        if (locked.isEmpty() || locked.get().getStatus() != ExamRoomStatus.AVAILABLE) {
            return Optional.empty();
        }
        ExamRoom room = locked.get();

        LocalDateTime now = now();
        Optional<Employee> doctor = doctorInRoom(roomId, now);
        if (doctor.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime calledAt = OffsetDateTime.now(clock);
        Optional<Appointment> next = appointments.lockNextInQueue(
                doctor.get().getId(), now.toLocalDate(), calledAt.minus(recallAfter));
        if (next.isEmpty()) {
            return Optional.empty();
        }

        Appointment appointment = next.get();
        appointment.callInto(room, calledAt);
        room.occupy(appointment);
        appointments.persist(appointment);
        rooms.persist(room);
        events.publishEvent(new ExamRoomEvents.BoardChanged(room.getClinicId()));
        return Optional.of(appointment);
    }

    /**
     * The room, locked, and re-read after the lock is taken.
     *
     * <p>The re-read is the point. A caller that already read the room in this
     * transaction (to check it may) holds that copy in the persistence
     * context, and a locking query hands back the copy, not the row: without
     * it, a status another transaction changed while this one waited for the
     * lock would go unseen.
     */
    public Optional<ExamRoom> lock(Long roomId) {
        Optional<ExamRoom> room = rooms.findForUpdate(roomId);
        room.ifPresent(entityManager::refresh);
        return room;
    }

    /**
     * Closes out a visit left in the room from an earlier day (nobody pressed
     * "finish" before closing) and frees the room. Counted as completed: the
     * patient was called in and seen. What the sweep does each morning.
     *
     * @return whether a stale visit was closed
     */
    @Transactional
    public boolean closeStale(Long roomId) {
        Optional<ExamRoom> locked = lock(roomId);
        Appointment inside = locked.map(ExamRoom::getCurrentAppointment).orElse(null);
        if (inside == null || !inside.getAppointmentDate().isBefore(now().toLocalDate())) {
            return false;
        }
        ExamRoom room = locked.get();
        inside.complete();
        room.release();
        appointments.persist(inside);
        rooms.persist(room);
        events.publishEvent(new ExamRoomEvents.RoomFreed(room.getClinicId(), room.getId()));
        return true;
    }

    /**
     * The doctor whose session puts them in this room at this moment, if any,
     * and who has not blocked this moment off: a doctor on a blocked period
     * is not in the room, whatever the week says.
     */
    public Optional<Employee> doctorInRoom(Long roomId, LocalDateTime at) {
        return hours.findInRoomAt(roomId, (short) at.getDayOfWeek().getValue(), at.toLocalTime()).stream()
                .findFirst()
                .map(DoctorWorkingHours::getEmployee)
                .filter(doctor -> !isBlocked(doctor.getId(), at));
    }

    private boolean isBlocked(Long doctorId, LocalDateTime at) {
        return blocked.findAll(DoctorScheduleSpecifications.onDay(doctorId, at.toLocalDate())).stream()
                .anyMatch(period -> !period.getStartTime().isAfter(at.toLocalTime())
                        && period.getEndTime().isAfter(at.toLocalTime()));
    }

    /** The room the doctor's current session puts them in, if any. */
    public Optional<ExamRoom> roomOfDoctor(Long doctorId, LocalDateTime at) {
        return hours.findRoomOfDoctorAt(doctorId, (short) at.getDayOfWeek().getValue(), at.toLocalTime()).stream()
                .findFirst()
                .map(DoctorWorkingHours::getExamRoom);
    }

    /** Now, as the clinic's wall clock reads it. */
    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(clinicService.timeZone()));
    }
}
