package com.vn.vitalcare.care.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** {@code PUT /doctors/{id}/working-hours}: the doctor's whole week. An empty list means they see nobody. */
public record WorkingHoursRequest(
        @NotNull(message = "Sessions are required")
        List<@Valid WorkingHoursEntry> sessions) {
}
