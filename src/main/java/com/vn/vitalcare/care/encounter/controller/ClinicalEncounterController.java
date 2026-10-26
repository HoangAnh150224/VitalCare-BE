package com.vn.vitalcare.care.encounter.controller;

import com.vn.vitalcare.care.encounter.dto.EncounterRequest;
import com.vn.vitalcare.care.encounter.dto.EncounterResponse;
import com.vn.vitalcare.care.encounter.service.ClinicalEncounterService;
import com.vn.vitalcare.entity.ClinicalEncounter;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/clinical_encounters} — what was recorded at each patient visit.
 *
 * <p>No delete: an encounter is part of the patient's history.
 */
@RestController
@RequestMapping("/api/clinical_encounters")
public class ClinicalEncounterController {

    private final ClinicalEncounterService service;
    private final RowLevelSecurity rowLevel;

    public ClinicalEncounterController(ClinicalEncounterService service, RowLevelSecurity rowLevel) {
        this.service = service;
        this.rowLevel = rowLevel;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_ENCOUNTERS_READ + "')")
    public ResponseEntity<List<EncounterResponse>> list(@RequestParam MultiValueMap<String, String> query) {
        ListParams params = new ListParams(query);

        List<Long> ids = params.ids();
        if (!ids.isEmpty()) {
            List<EncounterResponse> rows = service.getMany(ids).stream().map(this::response).toList();
            return ListResponse.of(rows, rows.size());
        }

        Page<ClinicalEncounter> page = service.list(params);
        return ListResponse.of(page.map(this::response).getContent(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_ENCOUNTERS_READ + "')")
    public EncounterResponse get(@PathVariable Long id) {
        return response(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_ENCOUNTERS_WRITE + "')")
    public ResponseEntity<EncounterResponse> create(@Valid @RequestBody EncounterRequest request) {
        ClinicalEncounter created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/clinical_encounters/" + created.getId()))
                .body(response(created));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_ENCOUNTERS_WRITE + "')")
    public EncounterResponse update(@PathVariable Long id, @Valid @RequestBody EncounterRequest request) {
        return response(service.update(id, request));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('" + Permissions.CLINICAL_ENCOUNTERS_CONCLUDE + "')")
    public EncounterResponse complete(@PathVariable Long id) {
        return response(service.complete(id));
    }

    private EncounterResponse response(ClinicalEncounter encounter) {
        return EncounterResponse.from(encounter, rowLevel);
    }
}
