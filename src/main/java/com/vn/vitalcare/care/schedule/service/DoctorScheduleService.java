package com.vn.vitalcare.care.schedule.service;

import com.vn.vitalcare.care.schedule.dto.DoctorScheduleRequest;
import com.vn.vitalcare.entity.DoctorSchedule;
import com.vn.vitalcare.entity.Employee;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.data.domain.Page;

/**
 * The periods doctors block — time off inside the week they otherwise work
 * (SRS 3.11.7). A blocked period takes its turns out of the day's queue.
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
     * Refused when the period ends before it starts, lies in the past, shares
     * time with another the doctor blocked that day, or covers the time of a
     * visit somebody has already registered -- move or cancel that first.
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

    /**
     * The doctor whose schedule the caller may change: themselves for a
     * doctor, the named doctor of their clinic for a clinic administrator.
     * Shared with the working-hours endpoint, which follows the same rule.
     */
    Employee manageableDoctor(Long employeeId);

    /** The same rule as a test over doctors, resolved once: a doctor's own schedule, or anybody's for the rest. */
    Predicate<Employee> manageableByCaller();
}
