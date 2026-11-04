package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * {@code POST /appointments/{id}/reschedule}: another day, another doctor, or
 * both. The visit gets a new queue number there; its code stays.
 */
public record RescheduleRequest(
        /** The same doctor when left out. */
        Long employeeId,

        @NotNull(message = "A date is required")
        LocalDate appointmentDate) {
}
