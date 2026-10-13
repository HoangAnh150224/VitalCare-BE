package com.vn.vitalcare.care.medicalrecord.controller;

import com.vn.vitalcare.care.medicalrecord.dto.MedicalRecordRequest;
import com.vn.vitalcare.care.medicalrecord.dto.MedicalRecordResponse;
import com.vn.vitalcare.care.medicalrecord.service.MedicalRecordService;
import com.vn.vitalcare.share.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/customers/{customerId}/medical-record} — a patient's health background. */
@RestController
@RequestMapping("/api/customers/{customerId}/medical-record")
public class MedicalRecordController {

    private final MedicalRecordService service;

    public MedicalRecordController(MedicalRecordService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_RECORDS_READ + "')")
    public MedicalRecordResponse get(@PathVariable Long customerId) {
        return service.get(customerId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_RECORDS_WRITE + "')")
    public MedicalRecordResponse save(@PathVariable Long customerId, @Valid @RequestBody MedicalRecordRequest request) {
        return service.save(customerId, request);
    }
}
