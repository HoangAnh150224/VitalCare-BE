package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * {@code POST /my_appointments}: a customer booking for themselves.
 *
 * <p>There is no customer field, on purpose. Whose appointment it is comes
 * from the access token, so nothing a client sends can book for somebody
 * else.
 */
public record BookingRequest(
        @NotNull(message = "A clinic is required")
        UUID clinicId,

        @NotNull(message = "A date is required")
        LocalDate appointmentDate,

        // The start of one of the clinic's slots. No end time: the slot has one.
        @NotNull(message = "A start time is required")
        LocalTime startTime,

        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason) {
}
