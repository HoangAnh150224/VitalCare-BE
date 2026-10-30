package com.vn.vitalcare.care.examroom.service.impl;

import com.vn.vitalcare.care.appointment.repository.AppointmentRepository;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.care.examroom.dto.ExamRoomRequest;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.DoctorQueue;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.DoctorRef;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.RoomCard;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.Visit;
import com.vn.vitalcare.care.examroom.repository.ExamRoomRepository;
import com.vn.vitalcare.care.examroom.repository.ExamRoomSpecifications;
import com.vn.vitalcare.care.appointment.service.VisitEvents;
import com.vn.vitalcare.care.appointment.service.VisitReturns;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse.AwayVisit;
import com.vn.vitalcare.care.examroom.service.ExamRoomEvents;
import com.vn.vitalcare.care.examroom.service.ExamRoomService;
import com.vn.vitalcare.care.examroom.service.RoomDispatcher;
import com.vn.vitalcare.care.staff.service.ClinicScope;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.entity.ExamRoomStatus;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.data.BaseEntitySpecifications;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.exception.FieldValidationException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.web.ListParams;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link ExamRoomService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class ExamRoomServiceImpl implements ExamRoomService {

    private static final Set<String> SORTABLE = Set.of("id", "code", "name", "status");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("code"), Sort.Order.asc("id"));

    private final ExamRoomRepository rooms;
    private final AppointmentRepository appointments;
    private final RoomDispatcher dispatcher;
    private final ClinicService clinicService;
    private final ClinicScope clinicScope;
    private final RowLevelSecurity rowLevel;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final VisitReturns visitReturns;

    public ExamRoomServiceImpl(ExamRoomRepository rooms,
                               AppointmentRepository appointments,
                               RoomDispatcher dispatcher,
                               ClinicService clinicService,
                               ClinicScope clinicScope,
                               RowLevelSecurity rowLevel,
                               ApplicationEventPublisher events,
                               Clock clock,
                               VisitReturns visitReturns) {
        this.rooms = rooms;
        this.appointments = appointments;
        this.dispatcher = dispatcher;
        this.clinicService = clinicService;
        this.clinicScope = clinicScope;
        this.rowLevel = rowLevel;
        this.events = events;
        this.clock = clock;
        this.visitReturns = visitReturns;
    }

    @Override
    public Page<ExamRoom> list(ListParams params) {
        return rooms.findAll(
                rowLevel.scope(ExamRoom.class, Action.READ).and(ExamRoomSpecifications.from(params)),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    @Override
    public List<ExamRoom> getMany(List<Long> ids) {
        return rooms.findAll(Specification.allOf(
                rowLevel.byIds(ExamRoom.class, Action.READ, ids), BaseEntitySpecifications.notDeleted()));
    }

    @Override
    public ExamRoom get(Long id) {
        return find(id, Action.READ);
    }

    @Override
    @Transactional
    public ExamRoom create(ExamRoomRequest request) {
        Clinic clinic = clinicService.get(clinicFor(request.clinicId()));
        String code = request.code().trim();
        if (rooms.codeTaken(clinic.getClinicId(), code, null)) {
            throw new FieldValidationException("code", "Another room of this clinic already has that code");
        }

        ExamRoom room = new ExamRoom();
        room.setClinic(clinic);
        room.setCode(code);
        room.setName(blankToNull(request.name()));

        ExamRoom saved = rooms.persist(room);
        // WITH CHECK against the complete row, after the flush; see DeviceServiceImpl.register.
        rooms.flush();
        rowLevel.checkWritable(saved);
        return saved;
    }

    @Override
    @Transactional
    public ExamRoom update(Long id, ExamRoomRequest request) {
        ExamRoom room = find(id, Action.WRITE);
        String code = request.code().trim();
        if (rooms.codeTaken(room.getClinicId(), code, room.getId())) {
            throw new FieldValidationException("code", "Another room of this clinic already has that code");
        }
        room.setCode(code);
        room.setName(blankToNull(request.name()));
        rowLevel.checkWritable(room);
        return rooms.persist(room);
    }

    @Override
    public RoomBoardResponse board(UUID clinicId) {
        UUID clinic = clinicFor(clinicId);
        LocalDateTime now = dispatcher.now();

        List<ExamRoom> clinicRooms = new ArrayList<>(rooms.findAll(Specification.allOf(
                rowLevel.scope(ExamRoom.class, Action.READ),
                BaseEntitySpecifications.notDeleted(),
                ExamRoomSpecifications.ofClinic(clinic))));
        clinicRooms.sort(Comparator.comparing(ExamRoom::getCode, String.CASE_INSENSITIVE_ORDER));

        // Everyone waiting, per doctor, in call order; a room takes its doctor's
        // queue, and whatever is left over waits with no room.
        Map<Long, List<Appointment>> queues = new LinkedHashMap<>();
        Map<Long, Employee> doctors = new LinkedHashMap<>();
        for (Appointment waiting : appointments.findWaitingAt(clinic, now.toLocalDate())) {
            queues.computeIfAbsent(waiting.getEmployee().getId(), id -> new ArrayList<>()).add(waiting);
            doctors.putIfAbsent(waiting.getEmployee().getId(), waiting.getEmployee());
        }

        // Out of the room for services or another doctor, per doctor, with how far along they are.
        Map<Long, List<AwayVisit>> away = new LinkedHashMap<>();
        for (Appointment out : appointments.findAwayAt(clinic, now.toLocalDate())) {
            List<VisitReturns.Task> tasks = visitReturns.tasksOf(out);
            away.computeIfAbsent(out.getEmployee().getId(), id -> new ArrayList<>()).add(new AwayVisit(
                    Visit.of(out),
                    (int) tasks.stream().filter(VisitReturns.Task::done).count(),
                    tasks.size(),
                    tasks.stream().filter(task -> !task.done()).map(VisitReturns.Task::name).toList()));
            doctors.putIfAbsent(out.getEmployee().getId(), out.getEmployee());
        }

        List<RoomCard> cards = new ArrayList<>();
        // ponytail: one session lookup per room; a clinic has a handful. Batch it
        // into a single query if boards ever show dozens of rooms.
        for (ExamRoom room : clinicRooms) {
            Optional<Employee> doctor = dispatcher.doctorInRoom(room.getId(), now);
            List<Appointment> queue = doctor.map(d -> queues.remove(d.getId())).orElse(null);
            List<AwayVisit> out = doctor.map(d -> away.remove(d.getId())).orElse(null);
            Appointment current = room.getCurrentAppointment();
            cards.add(new RoomCard(
                    room.getId(),
                    room.getCode(),
                    room.getName(),
                    room.getStatus(),
                    doctor.map(DoctorRef::of).orElse(null),
                    current == null ? null : Visit.of(current),
                    queue == null ? List.of() : queue.stream().map(Visit::of).toList(),
                    out == null ? List.of() : out,
                    rowLevel.capabilities(room)));
        }

        // Doctors with nobody in a room for them: those waiting, and those away.
        java.util.Set<Long> left = new java.util.LinkedHashSet<>(queues.keySet());
        left.addAll(away.keySet());
        List<DoctorQueue> withoutRoom = left.stream()
                .map(id -> new DoctorQueue(
                        DoctorRef.of(doctors.get(id)),
                        queues.getOrDefault(id, List.of()).stream().map(Visit::of).toList(),
                        away.getOrDefault(id, List.of())))
                .toList();
        return new RoomBoardResponse(clinic, OffsetDateTime.now(clock), cards, withoutRoom);
    }

    @Override
    @Transactional
    public RoomBoardResponse callNext(Long roomId) {
        ExamRoom room = lockForWrite(roomId);
        if (room.getStatus() == ExamRoomStatus.PAUSED) {
            throw new ConflictException("This room is paused; reopen it first");
        }
        if (room.getStatus() == ExamRoomStatus.OCCUPIED) {
            throw new ConflictException("Somebody is still in this room; finish or pass them over first");
        }
        if (dispatcher.doctorInRoom(roomId, dispatcher.now()).isEmpty()) {
            throw new ConflictException("No doctor is scheduled in this room right now");
        }
        if (dispatcher.dispatch(roomId).isEmpty()) {
            throw new ConflictException("Nobody is waiting for this room's doctor");
        }
        return board(room.getClinicId());
    }

    @Override
    @Transactional
    public RoomBoardResponse passOver(Long roomId) {
        ExamRoom room = lockForWrite(roomId);
        Appointment current = requireOccupant(room);
        current.passOver(OffsetDateTime.now(clock));
        room.release();
        appointments.persist(current);
        rooms.persist(room);
        events.publishEvent(new ExamRoomEvents.RoomFreed(room.getClinicId(), room.getId()));
        return board(room.getClinicId());
    }

    @Override
    @Transactional
    public RoomBoardResponse noShow(Long roomId) {
        ExamRoom room = lockForWrite(roomId);
        Appointment current = requireOccupant(room);
        current.markNoShow();
        room.release();
        appointments.persist(current);
        rooms.persist(room);
        events.publishEvent(new ExamRoomEvents.RoomFreed(room.getClinicId(), room.getId()));
        events.publishEvent(new VisitEvents.VisitEnded(current.getId()));
        return board(room.getClinicId());
    }

    @Override
    @Transactional
    public RoomBoardResponse finish(Long roomId) {
        ExamRoom room = lockForWrite(roomId);
        Appointment current = requireOccupant(room);
        current.complete();
        room.release();
        appointments.persist(current);
        rooms.persist(room);
        events.publishEvent(new ExamRoomEvents.RoomFreed(room.getClinicId(), room.getId()));
        events.publishEvent(new VisitEvents.VisitEnded(current.getId()));
        return board(room.getClinicId());
    }

    @Override
    @Transactional
    public RoomBoardResponse pause(Long roomId) {
        ExamRoom room = lockForWrite(roomId);
        if (room.getStatus() == ExamRoomStatus.PAUSED) {
            throw new ConflictException("This room is already paused");
        }
        room.pause();
        rooms.persist(room);
        events.publishEvent(new ExamRoomEvents.BoardChanged(room.getClinicId()));
        return board(room.getClinicId());
    }

    @Override
    @Transactional
    public RoomBoardResponse resume(Long roomId) {
        ExamRoom room = lockForWrite(roomId);
        if (room.getStatus() != ExamRoomStatus.PAUSED) {
            throw new ConflictException("This room is not paused");
        }
        room.resume();
        rooms.persist(room);
        events.publishEvent(room.getStatus() == ExamRoomStatus.AVAILABLE
                ? new ExamRoomEvents.RoomFreed(room.getClinicId(), room.getId())
                : new ExamRoomEvents.BoardChanged(room.getClinicId()));
        return board(room.getClinicId());
    }

    @Override
    @Transactional
    public void releaseFor(Appointment appointment) {
        if (appointment.getExamRoom() == null) {
            return;
        }
        dispatcher.lock(appointment.getExamRoom().getId()).ifPresent(room -> {
            Appointment inside = room.getCurrentAppointment();
            if (inside != null && inside.getId().equals(appointment.getId())) {
                room.release();
                rooms.persist(room);
                events.publishEvent(new ExamRoomEvents.RoomFreed(room.getClinicId(), room.getId()));
            }
        });
    }

    /** The room through the write scope (another clinic's is not found), then locked and re-read. */
    private ExamRoom lockForWrite(Long roomId) {
        find(roomId, Action.WRITE);
        return dispatcher.lock(roomId).orElseThrow(() -> new ResourceNotFoundException("Exam room", roomId));
    }

    private static Appointment requireOccupant(ExamRoom room) {
        Appointment current = room.getCurrentAppointment();
        if (current == null) {
            throw new ConflictException("Nobody is in this room");
        }
        return current;
    }

    /**
     * The clinic a request is about: the caller's own when they work at one
     * (another clinic answers as not found), the one named otherwise.
     */
    private UUID clinicFor(UUID requested) {
        Optional<UUID> own = clinicScope.currentClinicId();
        if (own.isPresent()) {
            if (requested != null && !requested.equals(own.get())) {
                throw new ResourceNotFoundException("Clinic", requested);
            }
            return own.get();
        }
        if (requested == null) {
            throw new FieldValidationException("clinicId", "Pick a clinic");
        }
        return clinicService.get(requested).getClinicId();
    }

    private ExamRoom find(Long id, Action action) {
        return rooms.findOne(Specification.allOf(
                        rowLevel.byId(ExamRoom.class, action, id), BaseEntitySpecifications.notDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Exam room", id));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
