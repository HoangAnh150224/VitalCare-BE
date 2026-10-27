package com.vn.vitalcare.care.encounter.controller;

import com.vn.vitalcare.care.encounter.dto.MyVisitResult;
import com.vn.vitalcare.care.encounter.service.MyVisitResults;
import com.vn.vitalcare.care.serviceorder.ServiceOrders;
import com.vn.vitalcare.share.security.Permissions;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's own visit result, on their appointment:
 * {@code /api/my_appointments/{id}/result}. Read-only, pinned to the caller's
 * own appointments, and 404 until the visit is concluded.
 */
@RestController
@RequestMapping("/api/my_appointments/{appointmentId}/result")
public class MyVisitResultController {

    private final MyVisitResults service;

    public MyVisitResultController(MyVisitResults service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public MyVisitResult get(@PathVariable Long appointmentId) {
        return service.result(appointmentId);
    }

    /** Always a download, never shown inline, like the staff one: an uploaded file does not run in this origin. */
    @GetMapping("/files/{fileId}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_APPOINTMENTS_READ + "')")
    public ResponseEntity<Resource> download(@PathVariable Long appointmentId, @PathVariable Long fileId) {
        ServiceOrders.StoredFile file = service.file(appointmentId, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }
}
