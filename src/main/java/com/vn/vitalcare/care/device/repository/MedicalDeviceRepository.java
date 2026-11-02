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

    // Not scoped: a device reporting in is not anybody's request. VitalIngestion
    // locks it for the upload, so a resent batch waits and finds its readings stored.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from MedicalDevice d where d.deviceCode = :code and d.deletedAt is null")
    Optional<MedicalDevice> findForUpdateByCode(@Param("code") String code);

    @Query("select d from MedicalDevice d where d.deviceCode = :code and d.deletedAt is null")
    Optional<MedicalDevice> findByCode(@Param("code") String code);

    /** Online, but not heard from since the cutoff: DeviceHeartbeat's to mark offline. */
    @Query("select d from MedicalDevice d where d.connectivity = 'ONLINE' and d.deletedAt is null"
            + " and (d.lastSeenAt is null or d.lastSeenAt < :cutoff)")
    java.util.List<MedicalDevice> findGoneQuiet(@Param("cutoff") java.time.OffsetDateTime cutoff);

    /** 1 when this call took it offline; 0 when it reported in meanwhile or another sweep got there first. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MedicalDevice d set d.connectivity = 'OFFLINE' where d.id = :id and d.connectivity = 'ONLINE'"
            + " and (d.lastSeenAt is null or d.lastSeenAt < :cutoff)")
    int markOffline(@Param("id") Long id, @Param("cutoff") java.time.OffsetDateTime cutoff);
}
