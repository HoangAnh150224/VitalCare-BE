package com.vn.vitalcare.care.schedule.controller;

import com.vn.vitalcare.care.schedule.dto.DoctorScheduleRequest;
import com.vn.vitalcare.care.schedule.dto.DoctorScheduleResponse;
import com.vn.vitalcare.care.schedule.service.DoctorScheduleService;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/doctor_schedules} — when each doctor works, and when they are off. */
@RestController
@RequestMapping("/api/doctor_schedules")
public class DoctorScheduleController {

    private final DoctorScheduleService service;
    private final RowLevelSecurity rowLevel;

    public DoctorScheduleController(DoctorScheduleService service, RowLevelSecurity rowLevel) {
        this.service = service;
        this.rowLevel = rowLevel;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.DOCTOR_SCHEDULES_READ + "')")
    public ResponseEntity<List<DoctorScheduleResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<DoctorScheduleResponse> rows = service.getMany(ids).stream().map(responder()).toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<DoctorSchedule> page = service.list(params);
        return ListResponse.of(page.map(responder()).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.DOCTOR_SCHEDULES_READ + "')")
    public DoctorScheduleResponse get(@PathVariable Long id) {
        return response(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.DOCTOR_SCHEDULES_WRITE + "')")
    public ResponseEntity<DoctorScheduleResponse> create(@Valid @RequestBody DoctorScheduleRequest request) {
        DoctorSchedule created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/doctor_schedules/" + created.getId()))
                .body(response(created));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.DOCTOR_SCHEDULES_WRITE + "')")
    public DoctorScheduleResponse update(@PathVariable Long id, @Valid @RequestBody DoctorScheduleRequest request) {
        return response(service.update(id, request));
    }

    /** Echoes the deleted row. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.DOCTOR_SCHEDULES_DELETE + "')")
    public DoctorScheduleResponse delete(@PathVariable Long id) {
        // No capabilities on a row that is gone.
        return DoctorScheduleResponse.from(service.delete(id), null, true);
    }

    private DoctorScheduleResponse response(DoctorSchedule schedule) {
        return responder().apply(schedule);
    }

    /** Who the caller is is looked up once, not once per row. */
    private Function<DoctorSchedule, DoctorScheduleResponse> responder() {
        Predicate<DoctorSchedule> changeable = service.changeableByCaller();
        return schedule -> DoctorScheduleResponse.from(schedule, rowLevel, changeable.test(schedule));
    }
}
