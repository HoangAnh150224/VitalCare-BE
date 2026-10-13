package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /my_appointments}: a customer registering a visit for themselves.
 *
 * <p>A doctor and a day, nothing finer: the visit gets that doctor's next
 * queue number for the day, and the clinic is the doctor's. Whose account it
 * is comes from the access token; {@code patientId} only chooses among that
 * account's own profiles, so nothing a client sends registers for a stranger.
 */
public record BookingRequest(
        /**
         * Which of the account's profiles the visit is for: the owner's own
         * when left out, or one of the relatives under the account.
         */
        Long patientId,

        @NotNull(message = "A doctor is required")
        Long employeeId,

        @NotNull(message = "A date is required")
        LocalDate appointmentDate,

        @NotBlank(message = "A reason for the visit is required")
        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason) {
}
