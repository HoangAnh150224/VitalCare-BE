package com.vn.vitalcare.care.device.repository;

import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.data.SecuredRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicalDeviceRepository extends SecuredRepository<MedicalDevice, Long> {

    // Not scoped: uniqueness of a code or serial number is across every clinic.
    boolean existsByDeviceCodeIgnoreCase(String deviceCode);

    boolean existsBySerialNumberIgnoreCase(String serialNumber);

    /**
     * The device, locked until the transaction ends — taken before a device is
     * handed out or has its status changed, so two people giving out the last
     * free band at once cannot both succeed. The partial unique index on open
     * assignments is the backstop.
     */
    // Not scoped here -- a locking query takes no specification. The service
    // checks the row-level scope on the locked row before using it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from MedicalDevice d where d.id = :id and d.deletedAt is null")
    Optional<MedicalDevice> findForUpdate(@Param("id") Long id);
}
