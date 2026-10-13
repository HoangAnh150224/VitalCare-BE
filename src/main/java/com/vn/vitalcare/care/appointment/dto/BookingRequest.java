package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /my_appointments}: a customer registering a visit for themselves.
 *
 * <p>A doctor and a day, nothing finer: the visit gets that doctor's next
 * queue number for the day, and the clinic is the doctor's. There is no
 * customer field, on purpose — whose visit it is comes from the access token,
 * so nothing a client sends can register for somebody else.
 */
public record BookingRequest(
        @NotNull(message = "A doctor is required")
        Long employeeId,

        @NotNull(message = "A date is required")
        LocalDate appointmentDate,

        @NotBlank(message = "A reason for the visit is required")
        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason) {
}
