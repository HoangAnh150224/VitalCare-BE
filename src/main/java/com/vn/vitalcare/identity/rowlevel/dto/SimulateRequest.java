package com.vn.vitalcare.identity.rowlevel.dto;

import jakarta.validation.constraints.NotNull;

/** "Would this account be allowed to do this to that row?" */
public record SimulateRequest(
        @NotNull(message = "A resource is required") String resource,
        @NotNull(message = "An action is required") String action,
        @NotNull(message = "A user is required") Long userId,
        @NotNull(message = "A record is required") Long recordId) {
}
