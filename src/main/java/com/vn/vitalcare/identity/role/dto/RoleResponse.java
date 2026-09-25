package com.vn.vitalcare.identity.role.dto;

import com.vn.vitalcare.identity.permission.entity.Permission;
import com.vn.vitalcare.identity.role.entity.Role;
import java.util.Comparator;
import java.util.List;

/**
 * Wire shape of a role.
 *
 * <p>{@code permissions} is nested rather than flattened to an id list because
 * a multi-select UI binds its form to the objects it was given and renders
 * the codes on a show page.
 */
public record RoleResponse(
        Long id,
        String code,
        String name,
        String description,
        boolean systemRole,
        List<PermissionSummary> permissions) {

    /**
     * The permission as this endpoint reports it.
     *
     * <p>Shape-identical to the permission resource's own response today, and
     * declared here anyway: reusing that record would make the permission
     * API's payload part of this endpoint's contract, so widening one would
     * silently widen the other.
     */
    public record PermissionSummary(Long id, String code, String name) {

        static PermissionSummary from(Permission permission) {
            return new PermissionSummary(permission.getId(), permission.getCode(), permission.getName());
        }
    }

    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.isSystemRole(),
                role.getPermissions().stream()
                        // Sorted by code so the show page and the form list the
                        // grants in a stable order rather than in whatever
                        // order the join table came back in.
                        .sorted(Comparator.comparing(Permission::getCode))
                        .map(PermissionSummary::from)
                        .toList());
    }
}
