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
    private final com.vn.vitalcare.identity.audit.AuditTrail audit;

    public MedicalRecordController(MedicalRecordService service, com.vn.vitalcare.identity.audit.AuditTrail audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_RECORDS_READ + "')")
    public MedicalRecordResponse get(@PathVariable Long customerId) {
        MedicalRecordResponse record = service.get(customerId);
        // BR-24: every look at a patient's health data is on the record.
        audit.record("medical_records", customerId, "view", null, null);
        return record;
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + Permissions.MEDICAL_RECORDS_WRITE + "')")
    public MedicalRecordResponse save(@PathVariable Long customerId, @Valid @RequestBody MedicalRecordRequest request) {
        MedicalRecordResponse saved = service.save(customerId, request);
        audit.record("medical_records", customerId, "update", null, null);
        return saved;
    }
}
