package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * {@code POST /appointments}: the front desk booking on a customer's behalf —
 * somebody who phoned in, or who is standing at the counter.
 */
public record StaffBookingRequest(
        @NotNull(message = "A customer is required")
        Long customerId,

        @NotNull(message = "A clinic is required")
        UUID clinicId,

        @NotNull(message = "A date is required")
        LocalDate appointmentDate,

        // The start of one of the clinic's slots. No end time: the slot has one.
        @NotNull(message = "A start time is required")
        LocalTime startTime,

        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason,

        @Size(max = 2000, message = "Note must be at most 2000 characters")
        String note) {

    public BookingRequest booking() {
        return new BookingRequest(clinicId, appointmentDate, startTime, reason);
    }
}
