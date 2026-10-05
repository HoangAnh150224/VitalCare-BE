package com.vn.vitalcare.care.clinic.dto;

import com.vn.vitalcare.entity.ClinicWorkingHours;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * One session of a clinic's week, in and out.
 *
 * @param dayOfWeek ISO numbering, 1 = Monday … 7 = Sunday
 */
public record WorkingHoursEntry(
        @NotNull(message = "A weekday is required")
        @Min(value = 1, message = "Weekday must be 1 (Monday) to 7 (Sunday)")
        @Max(value = 7, message = "Weekday must be 1 (Monday) to 7 (Sunday)")
        Integer dayOfWeek,

        @NotNull(message = "An opening time is required")
        LocalTime openTime,

        @NotNull(message = "A closing time is required")
        LocalTime closeTime,

        @NotNull(message = "A slot length is required")
        @Min(value = 5, message = "A slot is at least 5 minutes")
        @Max(value = 240, message = "A slot is at most 240 minutes")
        Integer slotMinutes,

        @NotNull(message = "A capacity is required")
        @Min(value = 1, message = "A slot holds at least one person")
        @Max(value = 100, message = "A slot holds at most 100 people")
        Integer capacityPerSlot) {

    public static WorkingHoursEntry from(ClinicWorkingHours hours) {
        return new WorkingHoursEntry(
                hours.getDayOfWeek().getValue(),
                hours.getOpenTime(),
                hours.getCloseTime(),
                hours.getSlotMinutes(),
                hours.getCapacityPerSlot());
    }
}
