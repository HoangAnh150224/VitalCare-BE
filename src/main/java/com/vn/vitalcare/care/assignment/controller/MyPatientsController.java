package com.vn.vitalcare.care.assignment.controller;

import com.vn.vitalcare.care.assignment.dto.MyPatientResponse;
import com.vn.vitalcare.care.assignment.service.MyPatientsService;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/my_patients} — the patients the signed-in member of staff is
 * following. Unpaged: one person's caseload fits on a screen.
 */
@RestController
@RequestMapping("/api/my_patients")
public class MyPatientsController {

    private final MyPatientsService service;

    public MyPatientsController(MyPatientsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_PATIENTS_READ + "')")
    public ResponseEntity<List<MyPatientResponse>> list() {
        List<MyPatientResponse> rows = service.list();
        return ListResponse.of(rows, rows.size());
    }

    /** A patient the caller follows; anybody else's answers 404. */
    @GetMapping("/{customerId}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_PATIENTS_READ + "')")
    public MyPatientResponse get(@PathVariable Long customerId) {
        return service.get(customerId);
    }
}
