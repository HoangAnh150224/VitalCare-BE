package com.vn.vitalcare.care.billing.catalog;

import com.vn.vitalcare.entity.MedicalService;
import com.vn.vitalcare.entity.ServiceCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The service catalogue. Not under row-level management: one list for every clinic, readable by all staff. */
public interface MedicalServiceRepository extends JpaRepository<MedicalService, Long> {

    @Query("select s from MedicalService s where s.deletedAt is null order by s.category, s.name")
    List<MedicalService> findLive();

    @Query("select s from MedicalService s where s.id = :id and s.deletedAt is null")
    Optional<MedicalService> findLive(@Param("id") Long id);

    /** The examination fee: the first active CONSULTATION entry. */
    @Query("select s from MedicalService s where s.category = :category "
            + "and s.status = com.vn.vitalcare.entity.ServiceStatus.ACTIVE and s.deletedAt is null order by s.id")
    List<MedicalService> findActiveByCategory(@Param("category") ServiceCategory category);

    @Query("select count(s) > 0 from MedicalService s where lower(s.code) = lower(:code) and s.deletedAt is null "
            + "and (:excludeId is null or s.id <> :excludeId)")
    boolean codeTaken(@Param("code") String code, @Param("excludeId") Long excludeId);
}
