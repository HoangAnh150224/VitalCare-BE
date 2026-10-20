package com.vn.vitalcare.care.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /customers/{id}/activity-status} (SRS 3.8.5): active, inactive,
 * or paused monitoring. A reason is required for anything but active.
 */
public record ActivityStatusRequest(
        @NotBlank(message = "A status is required")
        @Pattern(regexp = "^(active|inactive|paused)$", message = "Status must be active, inactive or paused")
        String status,

        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason) {
}
