package com.vn.vitalcare.identity.role.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /roles} payload.
 *
 * <p>{@code systemRole} is not settable: it marks a role the application itself
 * depends on, so it is a property of the seed data rather than something an
 * admin can confer on a role they just invented.
 */
public record RoleRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 64, message = "Code must be at most 64 characters")
        // Upper snake case keeps the code usable as a ROLE_ authority and stops
        // two roles differing only by punctuation or case.
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                message = "Code must be upper case letters, digits and underscores, starting with a letter")
        String code,

        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        @NotNull(message = "Permissions are required")
        @Valid
        List<PermissionRef> permissions) {
}
