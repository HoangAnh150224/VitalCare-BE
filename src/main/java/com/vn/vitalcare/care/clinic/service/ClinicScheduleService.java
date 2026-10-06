package com.vn.vitalcare.care.clinic.service;

import com.vn.vitalcare.care.clinic.dto.WorkingHoursEntry;
import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.ClinicWorkingHours;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * A clinic's opening hours, and the bookable slots cut from them.
 *
 * <p>Slots are derived, never stored: each session is cut into consecutive
 * slots of its own length, and a remainder too short for a whole slot is left
 * unused. Changing the hours therefore needs no clean-up; appointments booked
 * under the old hours keep their times.
 */
public interface ClinicScheduleService {

    List<ClinicWorkingHours> week(Clinic clinic);

    /**
     * Every slot of a day with how full it is. Empty for a day the clinic is
     * closed. A slot that has already started today is {@code PAST}, whatever
     * its count.
     */
    List<Slot> slotsFor(Clinic clinic, LocalDate date);

    /** The slot of that day starting exactly at {@code start}, if there is one. Counts are not filled in. */
    Optional<Slot> slotStartingAt(Clinic clinic, LocalDate date, LocalTime start);

    /** Whether the clinic opens at all on that weekday. */
    boolean isOpenOn(Clinic clinic, LocalDate date);

    /** How many places in that slot are taken now. Called under the clinic lock. */
    long bookedIn(Clinic clinic, LocalDate date, LocalTime start);

    /**
     * Replaces a clinic's whole week.
     *
     * <p>Refused, on {@code sessions}, when two sessions of one day overlap or
     * a session closes before it opens — either would cut slots that make no
     * sense. Touching sessions (11:30 close, 11:30 open) are fine.
     */
    List<ClinicWorkingHours> replaceWeek(Clinic clinic, List<WorkingHoursEntry> sessions);
}
