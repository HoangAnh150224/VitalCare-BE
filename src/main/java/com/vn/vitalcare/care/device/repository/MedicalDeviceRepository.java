package com.vn.vitalcare.care.device.repository;

import com.vn.vitalcare.entity.MedicalDevice;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicalDeviceRepository extends JpaRepository<MedicalDevice, Long>, JpaSpecificationExecutor<MedicalDevice> {

    Optional<MedicalDevice> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByDeviceCodeIgnoreCase(String deviceCode);

    boolean existsBySerialNumberIgnoreCase(String serialNumber);

    /**
     * The device, locked until the transaction ends — taken before a device is
     * handed out or has its status changed, so two people giving out the last
     * free band at once cannot both succeed. The partial unique index on open
     * assignments is the backstop.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from MedicalDevice d where d.id = :id and d.deletedAt is null")
    Optional<MedicalDevice> findForUpdate(@Param("id") Long id);
}
