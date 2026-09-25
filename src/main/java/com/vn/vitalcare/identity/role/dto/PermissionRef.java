package com.vn.vitalcare.identity.role.dto;

import jakarta.validation.constraints.NotNull;

/**
 * A permission referred to by id in a role payload.
 *
 * <p>Nested rather than a bare id list because a multi-select UI round-trips
 * the objects it was given.
 */
public record PermissionRef(@NotNull(message = "Permission id is required") Long id) {
}
