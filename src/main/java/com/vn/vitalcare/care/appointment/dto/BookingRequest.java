package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /my_appointments}: a customer registering a visit for themselves.
 *
 * <p>A day, and the clinic when there is more than one: no doctor. The visit
 * takes the clinic's next number for the day and waits in the clinic's one
 * line; whichever room comes free calls the first in line, and that room's
 * doctor becomes the visit's (BF-02, shared queue). Whose account it
 * is comes from the access token; {@code patientId} only chooses among that
 * account's own profiles, so nothing a client sends registers for a stranger.
 */
public record BookingRequest(
        /**
         * Which of the account's profiles the visit is for: the owner's own
         * when left out, or one of the relatives under the account.
         */
        Long patientId,

        /** Older clients only: nobody chooses the doctor now; it names the clinic when clinicId is left out. */
        Long employeeId,

        /** Which clinic, when there is more than one. */
        java.util.UUID clinicId,

        @NotNull(message = "A date is required")
        LocalDate appointmentDate,

        @NotBlank(message = "A reason for the visit is required")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason,

        /**
         * The department to be seen in ("Tim mạch", "Hô hấp"): only its rooms call the visit in.
         * Left out by older clients, and then any room may.
         */
        @Size(max = 64, message = "The department must be at most 64 characters")
        String department) {

    /** Older clients, which do not name a department. */
    public BookingRequest(Long patientId, Long employeeId, java.util.UUID clinicId, LocalDate appointmentDate,
                          String reason) {
        this(patientId, employeeId, clinicId, appointmentDate, reason, null);
    }
}
