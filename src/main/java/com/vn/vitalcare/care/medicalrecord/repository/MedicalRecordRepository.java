package com.vn.vitalcare.care.medicalrecord.repository;

import com.vn.vitalcare.entity.MedicalRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, Long> {

    // Not scoped here: the service reaches the record only through
    // CustomerService.get, which applies the caller's clinic.
    Optional<MedicalRecord> findByCustomerIdAndDeletedAtIsNull(Long customerId);
}
