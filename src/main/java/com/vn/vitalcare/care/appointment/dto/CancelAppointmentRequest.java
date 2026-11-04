package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /appointments/{id}/cancel}: the clinic cancelling a customer's visit has to say why. */
public record CancelAppointmentRequest(
        @NotBlank(message = "A reason is required to cancel a customer's appointment")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason) {
}
