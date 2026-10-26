package com.vn.vitalcare.care.billing.catalog;

import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/medical_services} — the service catalogue and its prices. Unpaged: a clinic lists dozens, not thousands. */
@RestController
@RequestMapping("/api/medical_services")
public class MedicalServiceController {

    private final MedicalServiceCatalog catalog;

    public MedicalServiceController(MedicalServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_SERVICES_READ + "')")
    public ResponseEntity<List<MedicalServiceResponse>> list() {
        List<MedicalServiceResponse> rows = catalog.list().stream().map(MedicalServiceResponse::from).toList();
        return ListResponse.of(rows, rows.size());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_SERVICES_READ + "')")
    public MedicalServiceResponse get(@PathVariable Long id) {
        return MedicalServiceResponse.from(catalog.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_SERVICES_WRITE + "')")
    public ResponseEntity<MedicalServiceResponse> create(@Valid @RequestBody MedicalServiceRequest request) {
        var created = catalog.create(request);
        return ResponseEntity.created(URI.create("/api/medical_services/" + created.getId()))
                .body(MedicalServiceResponse.from(created));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_SERVICES_WRITE + "')")
    public MedicalServiceResponse update(@PathVariable Long id, @Valid @RequestBody MedicalServiceRequest request) {
        return MedicalServiceResponse.from(catalog.update(id, request));
    }
}
