package com.vn.vitalcare.share.security.rowlevel;

import java.util.Set;
import java.util.UUID;

/**
 * Who the policies are being evaluated for.
 *
 * <p>Built per request from the authority cache — <em>not</em> from the access
 * token, and never captured at sign-in. Freezing it at login would leave
 * somebody who moved department this morning still reading their old
 * department's rows until their token expired, which is exactly the staleness
 * the token redesign existed to remove. Reading it from a cache that the
 * features changing the data invalidate means a transfer takes effect on the
 * very next request, and costs no query to do it.
 *
 * @param userId         the account making the request
 * @param organizationId where it sits, or null
 * @param departmentId   which unit inside that organization, or null
 * @param clinicId       the clinic its staff record places it at, or null —
 *                       see {@link ClinicPlacement}
 * @param roleCodes      role codes, for a policy that wants to name one
 * @param roleIds        role ids, which is what a {@code SCOPE} is keyed by
 */
public record RowLevelPrincipal(
        long userId,
        Long organizationId,
        Long departmentId,
        UUID clinicId,
        Set<String> roleCodes,
        Set<Long> roleIds) {

    public RowLevelPrincipal {
        roleCodes = Set.copyOf(roleCodes);
        roleIds = Set.copyOf(roleIds);
    }

    /** A principal placed at no clinic — the framework's shape. */
    public RowLevelPrincipal(
            long userId, Long organizationId, Long departmentId, Set<String> roleCodes, Set<Long> roleIds) {
        this(userId, organizationId, departmentId, null, roleCodes, roleIds);
    }

    public boolean hasRole(long roleId) {
        return roleIds.contains(roleId);
    }
}
