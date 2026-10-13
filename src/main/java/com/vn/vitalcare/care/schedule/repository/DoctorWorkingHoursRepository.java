package com.vn.vitalcare.care.schedule.repository;

import com.vn.vitalcare.entity.DoctorWorkingHours;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A doctor's week. Whose week a caller may read or replace is the service's to decide. */
public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {

    @Query("select h from DoctorWorkingHours h where h.employee.id = :employeeId order by h.dayOfWeek, h.startTime")
    List<DoctorWorkingHours> findWeek(@Param("employeeId") Long employeeId);

    @Query("select h from DoctorWorkingHours h where h.employee.id = :employeeId and h.dayOfWeek = :dayOfWeek "
            + "order by h.startTime")
    List<DoctorWorkingHours> findDay(@Param("employeeId") Long employeeId, @Param("dayOfWeek") short dayOfWeek);

    /**
     * Clears a doctor's week before the replacement is written. A hard delete:
     * the rows are configuration with nothing pointing at them, and visits
     * registered under the old hours keep their own number and time.
     */
    @Modifying
    @Query("delete from DoctorWorkingHours h where h.employee.id = :employeeId")
    int deleteWeek(@Param("employeeId") Long employeeId);
}
