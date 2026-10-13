package com.vn.vitalcare.care.schedule.dto;

import com.vn.vitalcare.entity.DoctorWorkingHours;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/** One session of a doctor's week, on the wire both ways. */
public record WorkingHoursEntry(
        /** ISO: 1 = Monday … 7 = Sunday. */
        @NotNull(message = "A weekday is required")
        @Min(value = 1, message = "Weekday must be 1 (Monday) to 7 (Sunday)")
        @Max(value = 7, message = "Weekday must be 1 (Monday) to 7 (Sunday)")
        Integer dayOfWeek,

        @NotNull(message = "A start time is required")
        LocalTime startTime,

        @NotNull(message = "An end time is required")
        LocalTime endTime,

        /** How long one patient's visit takes; the session holds as many patients as fit. */
        @NotNull(message = "Minutes per visit is required")
        @Min(value = 5, message = "A visit takes at least 5 minutes")
        @Max(value = 240, message = "A visit takes at most 240 minutes")
        Integer visitMinutes) {

    public static WorkingHoursEntry from(DoctorWorkingHours hours) {
        return new WorkingHoursEntry(hours.getDayOfWeek().getValue(), hours.getStartTime(), hours.getEndTime(),
                hours.getVisitMinutes());
    }
}
