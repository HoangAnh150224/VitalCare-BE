package com.vn.vitalcare.care.clinic.repository;

import com.vn.vitalcare.entity.ClinicWorkingHours;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClinicWorkingHoursRepository extends JpaRepository<ClinicWorkingHours, Long> {

    @Query("select h from ClinicWorkingHours h where h.clinic.clinicId = :clinicId and h.deletedAt is null "
            + "order by h.dayOfWeek, h.openTime")
    List<ClinicWorkingHours> findWeek(@Param("clinicId") UUID clinicId);

    @Query("select h from ClinicWorkingHours h where h.clinic.clinicId = :clinicId "
            + "and h.dayOfWeek = :dayOfWeek and h.deletedAt is null order by h.openTime")
    List<ClinicWorkingHours> findDay(@Param("clinicId") UUID clinicId, @Param("dayOfWeek") short dayOfWeek);

    /**
     * Clears a clinic's week before the replacement is written. A hard delete:
     * the rows are configuration with nothing pointing at them, and the
     * appointments booked under the old hours keep their own times.
     */
    @Modifying
    @Query("delete from ClinicWorkingHours h where h.clinic.clinicId = :clinicId")
    int deleteWeek(@Param("clinicId") UUID clinicId);
}
