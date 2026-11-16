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

        /** Ignored, as on the customer's side: the room that calls the visit in gives it its doctor. */
        Long employeeId,

        /** Which clinic, when there is more than one. */
        java.util.UUID clinicId,

        @NotNull(message = "A date is required")
        LocalDate appointmentDate,

        @NotBlank(message = "A reason for the visit is required")
        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason,

        @Size(max = 2000, message = "Note must be at most 2000 characters")
        String note,

        /** The department to be seen in; see {@link BookingRequest#department()}. */
        @Size(max = 64, message = "The department must be at most 64 characters")
        String department) {

    /** Callers that do not name a department. */
    public StaffBookingRequest(Long customerId, Long employeeId, java.util.UUID clinicId, LocalDate appointmentDate,
                               String reason, String note) {
        this(customerId, employeeId, clinicId, appointmentDate, reason, note, null);
    }


    public BookingRequest booking() {
        return new BookingRequest(null, employeeId, clinicId, appointmentDate, reason, department);
    }
}
