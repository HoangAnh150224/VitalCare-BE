package com.vn.vitalcare.care.medicalrecord.service.impl;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.medicalrecord.dto.MedicalRecordRequest;
import com.vn.vitalcare.care.medicalrecord.dto.MedicalRecordResponse;
import com.vn.vitalcare.care.medicalrecord.repository.MedicalRecordRepository;
import com.vn.vitalcare.care.medicalrecord.service.MedicalRecordService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.CustomerStatus;
import com.vn.vitalcare.entity.MedicalRecord;
import com.vn.vitalcare.share.exception.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link MedicalRecordService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class MedicalRecordServiceImpl implements MedicalRecordService {

    private final MedicalRecordRepository repository;
    // The clinic boundary: a patient this caller's clinic cannot see is not
    // found, and so neither is their record.
    private final CustomerService customerService;

    public MedicalRecordServiceImpl(MedicalRecordRepository repository, CustomerService customerService) {
        this.repository = repository;
        this.customerService = customerService;
    }

    @Override
    public MedicalRecordResponse get(Long customerId) {
        customerService.get(customerId);
        return repository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .map(record -> MedicalRecordResponse.from(customerId, record))
                .orElseGet(() -> MedicalRecordResponse.empty(customerId));
    }

    @Override
    @Transactional
    public MedicalRecordResponse save(Long customerId, MedicalRecordRequest request) {
        // Locked, so two first writes for one patient cannot both insert; the
        // unique index on customer_id is the backstop.
        Customer customer = customerService.getForUpdate(customerId);
        if (customer.getStatus() != CustomerStatus.PATIENT) {
            throw new ConflictException("Only a patient has a medical record; activate them first");
        }
        MedicalRecord record = repository.findByCustomerIdAndDeletedAtIsNull(customerId).orElseGet(() -> {
            MedicalRecord created = new MedicalRecord();
            created.setCustomer(customer);
            return created;
        });
        if (record.getId() != null && request.version() != null && request.version() != record.getVersion()) {
            throw new ConflictException("This record was changed by somebody else; reload it and try again");
        }
        record.setBloodType(blankToNull(request.bloodType()));
        record.setAllergies(blankToNull(request.allergies()));
        record.setMedicalHistory(blankToNull(request.medicalHistory()));
        record.setFamilyHistory(blankToNull(request.familyHistory()));
        record.setCurrentMedications(blankToNull(request.currentMedications()));
        record.setNote(blankToNull(request.note()));
        return MedicalRecordResponse.from(customerId, repository.saveAndFlush(record));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
