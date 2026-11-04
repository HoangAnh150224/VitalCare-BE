package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.MeasurementSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeasurementSessionRepository extends JpaRepository<MeasurementSession, Long> {

    Optional<MeasurementSession> findByDeviceIdAndClientSessionId(Long deviceId, String clientSessionId);
}
