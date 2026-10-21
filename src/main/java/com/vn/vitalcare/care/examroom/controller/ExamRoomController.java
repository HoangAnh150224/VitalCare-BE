package com.vn.vitalcare.care.examroom.controller;

import com.vn.vitalcare.care.examroom.dto.ExamRoomRequest;
import com.vn.vitalcare.care.examroom.dto.ExamRoomResponse;
import com.vn.vitalcare.care.examroom.dto.RoomBoardResponse;
import com.vn.vitalcare.care.examroom.service.ExamRoomService;
import com.vn.vitalcare.entity.ExamRoom;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
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

    public ExamRoomController(ExamRoomService service, RowLevelSecurity rowLevel) {
        this.service = service;
        this.rowLevel = rowLevel;
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
    public RoomBoardResponse passOver(@PathVariable Long id) {
        return service.passOver(id);
    }

    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_OPERATE + "')")
    public RoomBoardResponse noShow(@PathVariable Long id) {
        return service.noShow(id);
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasAuthority('" + Permissions.EXAM_ROOMS_OPERATE + "')")
    public RoomBoardResponse finish(@PathVariable Long id) {
        return service.finish(id);
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
