package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * {@code POST /appointments/{id}/reschedule}: another of the same clinic's
 * slots. The clinic does not change -- a visit somewhere else is a new booking.
 */
public record RescheduleRequest(
        @NotNull(message = "A date is required")
        LocalDate appointmentDate,

        // The start of one of the clinic's slots, as in a booking.
        @NotNull(message = "A start time is required")
        LocalTime startTime) {
}
