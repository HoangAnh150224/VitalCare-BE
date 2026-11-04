package com.vn.vitalcare.care.medicalrecord.controller;

import com.vn.vitalcare.care.medicalrecord.dto.MonitoringNoteRequest;
import com.vn.vitalcare.care.medicalrecord.dto.MonitoringNoteResponse;
import com.vn.vitalcare.care.medicalrecord.service.MonitoringNoteService;
import com.vn.vitalcare.share.security.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/customers/{customerId}/monitoring-notes} — notes kept while following a patient. */
@RestController
@RequestMapping("/api/customers/{customerId}/monitoring-notes")
public class MonitoringNoteController {

    private final MonitoringNoteService service;

    public MonitoringNoteController(MonitoringNoteService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_RECORDS_READ + "')")
    public List<MonitoringNoteResponse> list(@PathVariable Long customerId) {
        return service.list(customerId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_RECORDS_WRITE + "')")
    public MonitoringNoteResponse add(@PathVariable Long customerId, @Valid @RequestBody MonitoringNoteRequest request) {
        return service.add(customerId, request);
    }

    @RequestMapping(value = "/{noteId}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_RECORDS_WRITE + "')")
    public MonitoringNoteResponse update(@PathVariable Long customerId, @PathVariable Long noteId,
                                         @Valid @RequestBody MonitoringNoteRequest request) {
        return service.update(customerId, noteId, request);
    }
}
