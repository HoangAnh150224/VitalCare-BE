package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.VitalSigns;
import org.springframework.data.jpa.repository.JpaRepository;

/** Readings as the intake stores them; what staff and patients read goes through the views of FE-04. */
public interface VitalSignsRepository extends JpaRepository<VitalSigns, Long> {

    boolean existsByDeviceIdAndClientRecordId(Long deviceId, String clientRecordId);

    /** A device's latest readings, newest first, with whose they are. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "customer")
    java.util.List<VitalSigns> findByDeviceIdOrderByMeasuredAtDescIdDesc(Long deviceId,
                                                                         org.springframework.data.domain.Pageable page);
}
