package com.vn.vitalcare.care.medicalrecord.repository;

import com.vn.vitalcare.entity.MonitoringNote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Notes are reached through their patient, whose clinic boundary the service checks first. */
public interface MonitoringNoteRepository extends JpaRepository<MonitoringNote, Long> {

    List<MonitoringNote> findByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long customerId);

    Optional<MonitoringNote> findByIdAndCustomerIdAndDeletedAtIsNull(Long id, Long customerId);
}
