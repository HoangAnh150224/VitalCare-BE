package com.vn.vitalcare.care.schedule.repository;

import com.vn.vitalcare.entity.DoctorWorkingHours;
import java.time.LocalTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A doctor's week. Whose week a caller may read or replace is the service's to decide. */
public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {

    @Query("select h from DoctorWorkingHours h left join fetch h.examRoom where h.employee.id = :employeeId "
            + "order by h.dayOfWeek, h.startTime")
    List<DoctorWorkingHours> findWeek(@Param("employeeId") Long employeeId);

    /** The session that has a doctor in this room at this moment, if any; with the doctor and their account. */
    @Query("select h from DoctorWorkingHours h join fetch h.employee e join fetch e.user "
            + "where h.examRoom.id = :roomId and h.examRoom.deletedAt is null and h.dayOfWeek = :dayOfWeek "
            + "and h.startTime <= :at and h.endTime > :at")
    List<DoctorWorkingHours> findInRoomAt(@Param("roomId") Long roomId, @Param("dayOfWeek") short dayOfWeek,
                                          @Param("at") LocalTime at);

    /** The doctor's session at this moment that names a room, if any. */
    @Query("select h from DoctorWorkingHours h join fetch h.examRoom "
            + "where h.employee.id = :employeeId and h.examRoom.deletedAt is null and h.dayOfWeek = :dayOfWeek "
            + "and h.startTime <= :at and h.endTime > :at")
    List<DoctorWorkingHours> findRoomOfDoctorAt(@Param("employeeId") Long employeeId,
                                                @Param("dayOfWeek") short dayOfWeek, @Param("at") LocalTime at);

    /** Another doctor's session in the same room that overlaps a period: what a new session would clash with. */
    @Query("select h from DoctorWorkingHours h join fetch h.employee e join fetch e.user "
            + "where h.examRoom.id = :roomId and h.dayOfWeek = :dayOfWeek and e.id <> :employeeId "
            + "and h.startTime < :end and h.endTime > :start")
    List<DoctorWorkingHours> findClashes(@Param("roomId") Long roomId, @Param("dayOfWeek") short dayOfWeek,
                                         @Param("start") LocalTime start, @Param("end") LocalTime end,
                                         @Param("employeeId") Long employeeId);

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
