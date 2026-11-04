package com.vn.vitalcare.care.medicalrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST} and {@code PATCH /customers/{id}/monitoring-notes}. */
public record MonitoringNoteRequest(
        @NotBlank(message = "A note is required")
        @Size(max = 5000, message = "A note must be at most 5000 characters")
        String content) {
}
