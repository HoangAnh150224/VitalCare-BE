package com.vn.vitalcare.care.schedule.service;

import com.vn.vitalcare.care.schedule.dto.DayAvailability;
import com.vn.vitalcare.care.schedule.dto.WorkingHoursEntry;
import com.vn.vitalcare.entity.DoctorWorkingHours;
import com.vn.vitalcare.entity.Employee;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * When a doctor sees patients, and what a day of it holds.
 *
 * <p>A visit is registered with a doctor for a day, not for a time: the
 * patient gets the day's next queue number. The day's size comes from here —
 * the weekday's sessions cut into visits, less whatever the doctor has blocked
 * — and so does the time a number can expect to be called.
 */
public interface DoctorAvailabilityService {

    /** A doctor's week, for somebody of the same clinic; another clinic's doctor is not found. */
    List<DoctorWorkingHours> week(Long employeeId);

    /** The same, for a doctor already in hand; no check. */
    List<DoctorWorkingHours> weekOf(Employee doctor);

    /**
     * Replaces a doctor's whole week. Refused, on {@code sessions}, when a
     * session ends before it starts or two sessions of one day overlap.
     * Visits already registered keep their number and time.
     */
    List<DoctorWorkingHours> replaceWeek(Employee doctor, List<WorkingHoursEntry> sessions);

    /** The turns of one day, in order: turn N is where queue number N is expected. Empty on a day off. */
    List<Turn> turns(Employee doctor, LocalDate date);

    /**
     * The turn a new queue number can expect.
     *
     * <p>Before the day has started that is the number's own turn. Once it
     * has, the turns already past are gone: the number waits behind the
     * {@code waiting} people still to be seen, from the next turn ahead —
     * somebody registering at the desk at 14:00 is not told 08:00. A number
     * past the end of the day waits at the last turn.
     *
     * <p>Empty when the day has no turn left for one more: none ahead, or all
     * of those ahead already spoken for.
     */
    Optional<Turn> turnFor(List<Turn> turns, LocalDate date, int queueNumber, long waiting);

    /** What registering with this doctor on this day would get, without registering. */
    DayAvailability day(Employee doctor, LocalDate date);
}
