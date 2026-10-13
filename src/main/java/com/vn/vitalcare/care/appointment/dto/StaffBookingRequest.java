package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /appointments}: the front desk registering a visit on a
 * customer's behalf — somebody who phoned in, or who is standing at the counter.
 */
public record StaffBookingRequest(
        @NotNull(message = "A customer is required")
        Long customerId,

        @NotNull(message = "A doctor is required")
        Long employeeId,

        @NotNull(message = "A date is required")
        LocalDate appointmentDate,

        @NotBlank(message = "A reason for the visit is required")
        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason,

        @Size(max = 2000, message = "Note must be at most 2000 characters")
        String note) {

    public BookingRequest booking() {
        return new BookingRequest(null, employeeId, appointmentDate, reason);
    }
}
