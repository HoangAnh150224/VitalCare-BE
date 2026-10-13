package com.vn.vitalcare.care.schedule.service;

import com.vn.vitalcare.care.schedule.dto.DoctorScheduleRequest;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.data.domain.Page;

/**
 * Doctors' working periods and time off (SRS 3.11.6, 3.11.7).
 *
 * <p>Everybody who may read sees their clinic's schedule. Writing is narrower
 * than the clinic for one kind of caller: a doctor manages their own periods
 * and nobody else's, while a clinic administrator manages every doctor's.
 * Somebody else's period answers a doctor as not found.
 */
public interface DoctorScheduleService {

    Page<DoctorSchedule> list(ListParams params);

    List<DoctorSchedule> getMany(List<Long> ids);

    DoctorSchedule get(Long id);

    /**
     * Refused when the period ends before it starts, lies in the past, or
     * shares time with another of the same doctor's periods that day.
     */
    DoctorSchedule create(DoctorScheduleRequest request);

    DoctorSchedule update(Long id, DoctorScheduleRequest request);

    DoctorSchedule delete(Long id);

    /**
     * Whether the caller may change a period, beyond what their row-level
     * scope says: false for a doctor looking at a colleague's. Resolved once,
     * for a whole page of rows.
     */
    Predicate<DoctorSchedule> changeableByCaller();
}
