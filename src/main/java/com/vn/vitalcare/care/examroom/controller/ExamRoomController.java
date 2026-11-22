package com.vn.vitalcare.care.examroom.controller;

import com.vn.vitalcare.care.examroom.dto.ExamRoomRequest;
import com.vn.vitalcare.care.examroom.dto.ExamRoomResponse;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse;
import com.vn.vitalcare.care.examroom.service.ExamRoomService;
import com.vn.vitalcare.care.staff.service.EmployeeService;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.exception.ConflictException;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/exam_rooms} — a clinic's examination rooms, and the floor board
 * that shows who is in which room and who waits for whom.
 *
 * <p>The actions on a room answer with the clinic's whole board, which is
 * what the screen redraws after any of them.
 */
@RestController
@RequestMapping("/api/exam_rooms")
public class ExamRoomController {

    private final ExamRoomService service;
    private final RowLevelSecurity rowLevel;
    private final EmployeeService employees;

    public ExamRoomController(ExamRoomService service, RowLevelSecurity rowLevel, EmployeeService employees) {
        this.service = service;
        this.rowLevel = rowLevel;
        this.employees = employees;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_READ + "')")
    public ResponseEntity<List<ExamRoomResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<ExamRoomResponse> rows = service.getMany(ids).stream().map(this::response).toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<ExamRoom> page = service.list(params);
        return ListResponse.of(page.map(this::response).getContent(), page.getTotalElements());
    }

    /** Before {@code /{id}}, so "board" is not read as an id. */
    @GetMapping("/board")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_READ + "')")
    public RoomBoardResponse board(@RequestParam(required = false) UUID clinicId) {
        return service.board(clinicId);
    }

    /**
     * The caller's own slice of their clinic's board — a doctor's queue: the
     * room their session puts them in now (who is inside, who waits, who is
     * out for services), their patients waiting with no room, those still
     * to pay the fee who are theirs or, with no doctor yet, would join the
     * shared line of a room of theirs, and the clinic's shared line any free
     * room of theirs calls from. Nothing of another doctor's.
     *
     * <p>Answers an empty board to an account without a staff profile.
     * Before {@code /{id}}, so "mine" is not read as an id.
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_READ + "')")
    public RoomBoardResponse mine() {
        RoomBoardResponse board = service.board(null);
        Long me = myEmployeeId();
        if (me == null) {
            return new RoomBoardResponse(board.clinicId(), board.at(), List.of(), List.of(), List.of(), List.of());
        }
        List<RoomBoardResponse.RoomCard> myRooms =
                board.rooms().stream().filter(room -> isMe(room.doctor(), me)).toList();
        return new RoomBoardResponse(
                board.clinicId(),
                board.at(),
                myRooms,
                board.withoutRoom().stream().filter(queue -> isMe(queue.doctor(), me)).toList(),
                board.awaitingPayment() == null ? List.of()
                        : board.awaitingPayment().stream()
                                .filter(unpaid -> unpaid.doctor() != null
                                        ? isMe(unpaid.doctor(), me)
                                        : myRooms.stream().anyMatch(room -> calls(room, unpaid.visit())))
                                .toList(),
                board.sharedQueue() == null ? List.of() : board.sharedQueue());
    }

    /**
     * Whether the room would call this visit from the shared line once its fee
     * is paid: a room of a department calls only that department's patients
     * (and those of none). Keep in step with AppointmentRepository#lockNextInQueue.
     */
    private static boolean calls(RoomBoardResponse.RoomCard room, RoomBoardResponse.Visit visit) {
        return room.department() == null || visit.department() == null
                || room.department().equals(visit.department());
    }

    /** The caller's staff profile, or null for an account without one. */
    private Long myEmployeeId() {
        try {
            return employees.getForCurrentUser().getId();
        } catch (ConflictException noStaffProfile) {
            return null;
        }
    }

    private static boolean isMe(RoomBoardResponse.DoctorRef doctor, Long me) {
        return doctor != null && me.equals(doctor.id());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_READ + "')")
    public ExamRoomResponse get(@PathVariable Long id) {
        return response(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_WRITE + "')")
    public ResponseEntity<ExamRoomResponse> create(@Valid @RequestBody ExamRoomRequest request) {
        ExamRoom created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/exam_rooms/" + created.getId()))
                .body(response(created));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_WRITE + "')")
    public ExamRoomResponse update(@PathVariable Long id, @Valid @RequestBody ExamRoomRequest request) {
        return response(service.update(id, request));
    }

    @PostMapping("/{id}/call-next")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_OPERATE + "')")
    public RoomBoardResponse callNext(@PathVariable Long id) {
        return service.callNext(id);
    }

    @PostMapping("/{id}/pass-over")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_OPERATE + "')")
    public RoomBoardResponse passOver(@PathVariable Long id, @RequestParam(required = false) Long appointmentId) {
        return service.passOver(id, appointmentId);
    }

    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_OPERATE + "')")
    public RoomBoardResponse noShow(@PathVariable Long id, @RequestParam(required = false) Long appointmentId) {
        return service.noShow(id, appointmentId);
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_OPERATE + "')")
    public RoomBoardResponse finish(@PathVariable Long id, @RequestParam(required = false) Long appointmentId) {
        return service.finish(id, appointmentId);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_OPERATE + "')")
    public RoomBoardResponse pause(@PathVariable Long id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_OPERATE + "')")
    public RoomBoardResponse resume(@PathVariable Long id) {
        return service.resume(id);
    }

    private ExamRoomResponse response(ExamRoom room) {
        return ExamRoomResponse.from(room, rowLevel);
    }
}
