package com.vn.vitalcare.care.appointment.controller;

import com.vn.vitalcare.care.appointment.dto.AppointmentResponse;
import com.vn.vitalcare.care.appointment.dto.StaffBookingRequest;
import com.vn.vitalcare.care.appointment.service.AppointmentService;
import com.vn.vitalcare.entity.Appointment;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/appointments} — the clinic's appointment book.
 *
 * <p>Cancelling is a state change, not a delete: a cancelled appointment stays
 * on the record.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.APPOINTMENTS_READ + "')")
    public ResponseEntity<List<AppointmentResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<AppointmentResponse> rows = service.getMany(ids).stream()
                    .map(AppointmentResponse::from)
                    .toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<Appointment> page = service.list(params);
        return ListResponse.of(page.map(AppointmentResponse::from).getContent(), page.getTotalElements());
    }

    /**
     * The appointment a slip's code names — what the front desk scans or types.
     * Declared before {@code /{id}} so "lookup" is never read as an id.
     */
    @GetMapping("/lookup")
    @PreAuthorize("hasAuthority('" + Permissions.APPOINTMENTS_READ + "')")
    public AppointmentResponse lookup(@RequestParam String code) {
        return AppointmentResponse.from(service.getByCode(code));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.APPOINTMENTS_READ + "')")
    public AppointmentResponse get(@PathVariable Long id) {
        return AppointmentResponse.from(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.APPOINTMENTS_WRITE + "')")
    public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody StaffBookingRequest request) {
        Appointment created = service.bookFor(request);
        return ResponseEntity
                .created(URI.create("/api/appointments/" + created.getId()))
                .body(AppointmentResponse.from(created));
    }

    /** Records the arrival; a neutral customer becomes a patient in the same step. */
    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasAuthority('" + Permissions.APPOINTMENTS_CHECK_IN + "')")
    public AppointmentResponse checkIn(@PathVariable Long id) {
        return AppointmentResponse.from(service.checkIn(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Permissions.APPOINTMENTS_WRITE + "')")
    public AppointmentResponse cancel(@PathVariable Long id) {
        return AppointmentResponse.from(service.cancel(id));
    }
}
