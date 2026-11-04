package com.vn.vitalcare.care.schedule.dto;

import com.vn.vitalcare.entity.Employee;
import java.util.List;

/**
 * A doctor as somebody choosing who to see reads them: who they are, the week
 * they work, and — when a date was asked about — what that day still offers.
 */
public record DoctorAvailabilityResponse(
        Long id,
        String fullName,
        String specialty,
        String professionalTitle,
        List<WorkingHoursEntry> workingHours,
        /** Null when no date was asked about. */
        DayAvailability day,
        /** Whether the caller may change this doctor's schedule; null where that is not being asked. */
        Boolean manageable) {

    public static DoctorAvailabilityResponse from(Employee doctor, List<WorkingHoursEntry> week, DayAvailability day,
                                                  Boolean manageable) {
        return new DoctorAvailabilityResponse(doctor.getId(), doctor.getUser().getFullName(), doctor.getSpecialty(),
                doctor.getProfessionalTitle(), week, day, manageable);
    }
}
