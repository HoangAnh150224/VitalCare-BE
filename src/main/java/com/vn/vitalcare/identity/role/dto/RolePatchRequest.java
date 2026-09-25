package com.vn.vitalcare.identity.role.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code PATCH /roles/{id}} payload — a {@code null} field means "leave it
 * unchanged".
 *
 * <p>Note the difference between {@code null} and an empty list for
 * {@code permissions}: omitting it leaves the grants alone, sending {@code []}
 * revokes all of them. That distinction is the whole reason the field is a
 * nullable list rather than one defaulted to empty.
 */
public record RolePatchRequest(
        @Size(min = 1, max = 64, message = "Code must be between 1 and 64 characters")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                message = "Code must be upper case letters, digits and underscores, starting with a letter")
        String code,

        @Size(min = 1, max = 255, message = "Name must be between 1 and 255 characters")
        String name,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        @Valid
        List<PermissionRef> permissions) {
}
