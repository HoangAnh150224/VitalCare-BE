package com.vn.vitalcare.identity.user.dto;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Wire shape of a user.
 *
 * <p>No password field of any kind, hashed or otherwise: this record is the
 * only way a user leaves the application, so the hash having no place to go is
 * what guarantees it never does.
 *
 * <p>{@code roles} is nested rather than flattened to an id list because a
 * list view renders the role names as badges and a form binds its
 * multi-select to the objects it was given.
 */
public record UserResponse(
        Long id,
        String phone,
        String email,
        String fullName,
        UserStatus status,
        List<RoleSummary> roles,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt) {

    /**
     * The role as this endpoint reports it.
     *
     * <p>Deliberately narrower than the role resource's own response — no
     * permission list, which a user row has no use for — and declared here
     * rather than imported so that widening the role endpoint cannot silently
     * widen this one.
     */
    public record RoleSummary(Long id, String code, String name) {

        static RoleSummary from(Role role) {
            return new RoleSummary(role.getId(), role.getCode(), role.getName());
        }
    }

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getPhone(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus(),
                user.getRoles().stream()
                        // Sorted by code so the badges keep a stable order
                        // rather than whatever order the join table returned.
                        .sorted(Comparator.comparing(Role::getCode))
                        .map(RoleSummary::from)
                        .toList(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getLastLoginAt());
    }
}
