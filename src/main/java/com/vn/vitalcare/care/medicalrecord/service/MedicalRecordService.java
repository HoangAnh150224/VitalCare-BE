package com.vn.vitalcare.care.medicalrecord.service;

import com.vn.vitalcare.care.medicalrecord.dto.MedicalRecordRequest;
import com.vn.vitalcare.care.medicalrecord.dto.MedicalRecordResponse;

/** A patient's health background (SRS 3.9): one record per patient, written when first needed. */
public interface MedicalRecordService {

    /** The patient's record, or an empty one when none has been written. Another clinic's patient is not found. */
    MedicalRecordResponse get(Long customerId);

    MedicalRecordResponse save(Long customerId, MedicalRecordRequest request);
}
