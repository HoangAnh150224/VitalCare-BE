package com.vn.vitalcare.care.schedule.dto;

import com.vn.vitalcare.entity.ScheduleStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * {@code POST} and {@code PATCH /doctor_schedules}: one period of a doctor's day.
 *
 * <p>{@code employeeId} is whose it is, and counts on create only: a doctor
 * leaves it out (it is theirs), a clinic administrator names the doctor. A
 * period does not move to another doctor afterwards.
 */
public record DoctorScheduleRequest(
        Long employeeId,

        @NotNull(message = "A date is required")
        LocalDate workDate,

        @NotNull(message = "A start time is required")
        LocalTime startTime,

        @NotNull(message = "An end time is required")
        LocalTime endTime,

        /** Working when left out. */
        ScheduleStatus status,

        @Size(max = 255, message = "Note must be at most 255 characters")
        String note) {
}
