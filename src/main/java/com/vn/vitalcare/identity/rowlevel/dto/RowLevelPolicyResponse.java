package com.vn.vitalcare.identity.rowlevel.dto;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicy;
import com.vn.vitalcare.identity.user.entity.User;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Wire shape of a policy.
 *
 * @param scope         the condition tree itself, not a string containing one.
 *                      The admin screen edits it as structure, and handing it
 *                      back as text would mean two parsers with two ideas of
 *                      what is valid
 * @param invalidReason why the loader had to disable this, when it did. Reported
 *                      beside the policy because the person who can fix it is
 *                      looking at this screen, not at a node's log
 * @param source        always {@code runtime} here. Source-declared policies are
 *                      reported by {@code /metadata} instead, read-only, so that
 *                      an administrator sees the whole of what is in force
 *                      rather than only the half they can edit
 */
public record RowLevelPolicyResponse(
        Long id,
        String kind,
        RoleSummary role,
        String resource,
        String action,
        String name,
        String policyGroup,
        String description,
        JsonNode scope,
        /**
         * The {@code WITH CHECK} clause, or null when it reuses {@code scope}.
         * Reported as null rather than filled in, so the screen can show the
         * difference between "no separate check" and "a check that happens to
         * match".
         */
        JsonNode checkScope,
        boolean enabled,
        String invalidReason,
        String source,
        UserSummary createdBy,
        UserSummary updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    /** The role as this endpoint reports it — no permissions, no description. */
    public record RoleSummary(Long id, String code, String name) {

        static RoleSummary from(Role role) {
            return new RoleSummary(role.getId(), role.getCode(), role.getName());
        }
    }

    /** Who touched it. Null once that account has been deleted. */
    public record UserSummary(Long id, String fullName) {

        static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getFullName());
        }
    }

    public static RowLevelPolicyResponse from(RowLevelPolicy policy, JsonNode scope, JsonNode checkScope) {
        return new RowLevelPolicyResponse(
                policy.getId(),
                policy.getKind().name(),
                policy.getRole() == null ? null : RoleSummary.from(policy.getRole()),
                policy.getResource(),
                policy.getAction(),
                policy.getName(),
                policy.getPolicyGroup(),
                policy.getDescription(),
                scope,
                checkScope,
                policy.isEnabled(),
                policy.getInvalidReason(),
                "runtime",
                policy.getCreatedBy() == null ? null : UserSummary.from(policy.getCreatedBy()),
                policy.getUpdatedBy() == null ? null : UserSummary.from(policy.getUpdatedBy()),
                policy.getCreatedAt(),
                policy.getUpdatedAt());
    }
}
