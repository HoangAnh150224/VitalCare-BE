package com.vn.vitalcare.care.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /appointments/{id}}: what the desk may correct on a registered
 * visit. Who, with whom and when are not here — changing those is a
 * reschedule, which issues a new number.
 */
public record AppointmentUpdateRequest(
        @NotBlank(message = "A reason for the visit is required")
        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason,

        @Size(max = 2000, message = "Note must be at most 2000 characters")
        String note) {
}
