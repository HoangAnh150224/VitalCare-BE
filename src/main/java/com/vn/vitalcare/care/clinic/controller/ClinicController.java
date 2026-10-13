package com.vn.vitalcare.care.clinic.controller;

import com.vn.vitalcare.care.clinic.dto.ClinicRequest;
import com.vn.vitalcare.care.clinic.dto.ClinicResponse;
import com.vn.vitalcare.care.clinic.service.ClinicService;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/clinics} — where to book, when it is open, and what is free.
 *
 * <p>Reading needs no permission beyond being signed in: which clinics exist,
 * their hours and their free slots are what a clinic puts on its front door,
 * and every booking screen needs them. Changing the hours needs
 * {@code clinics:write}.
 */
@RestController
@RequestMapping("/api/clinics")
public class ClinicController {

    private final ClinicService service;

    public ClinicController(ClinicService service) {
        this.service = service;
    }

    /** Unpaged: the list is short and every caller wants all of it. */
    @GetMapping
    public ResponseEntity<List<ClinicResponse>> list() {
        List<ClinicResponse> rows = service.listAll().stream().map(ClinicResponse::from).toList();
        return ListResponse.of(rows, rows.size());
    }

    @GetMapping("/{id}")
    public ClinicResponse get(@PathVariable UUID id) {
        return ClinicResponse.from(service.get(id));
    }

    /** SRS 3.18.2 / 3.18.4: creating and editing a clinic is the System Administrator's (clinics:write). */
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.CLINICS_WRITE + "')")
    public ResponseEntity<ClinicResponse> create(@Valid @RequestBody ClinicRequest request) {
        Clinic created = service.create(request);
        return ResponseEntity.created(URI.create("/api/clinics/" + created.getClinicId()))
                .body(ClinicResponse.from(created));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.CLINICS_WRITE + "')")
    public ClinicResponse update(@PathVariable UUID id, @Valid @RequestBody ClinicRequest request) {
        return ClinicResponse.from(service.update(id, request));
    }
}
