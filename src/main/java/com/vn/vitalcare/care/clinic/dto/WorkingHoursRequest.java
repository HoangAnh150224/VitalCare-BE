package com.vn.vitalcare.care.clinic.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * {@code PUT /clinics/{id}/working-hours}: the whole week, replacing what was
 * there. An empty list closes the clinic every day.
 */
public record WorkingHoursRequest(
        @NotNull(message = "The sessions are required")
        List<@NotNull(message = "A session cannot be empty") @Valid WorkingHoursEntry> sessions) {
}
