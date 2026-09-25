package com.vn.vitalcare.identity.rowlevel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * A new policy.
 *
 * <p>Bean validation covers only the shape. Everything that makes a policy
 * <em>meaningful</em> — the resource being managed, the fields existing, the
 * operators suiting their types, the literals coercing, the context keys being
 * registered — is checked by {@code PolicyCompiler} when the service saves it,
 * and refused with a {@code 422} pointing at the offending node.
 *
 * @param scope the condition tree, as JSON. Taken as a raw node rather than a
 *              typed object so that a malformed tree produces the compiler's
 *              message about which node is wrong, instead of a deserialisation
 *              error about a field nobody wrote
 */
public record RowLevelPolicyRequest(
        @NotBlank(message = "A kind is required") String kind,
        RoleRef role,
        @NotBlank(message = "A resource is required") @Size(max = 64) String resource,
        @NotBlank(message = "An action is required") @Size(max = 16) String action,
        @NotBlank(message = "A name is required") @Size(max = 160) String name,
        @Size(max = 64) String policyGroup,
        @Size(max = 500) String description,
        @NotNull(message = "A scope is required") JsonNode scope,
        /**
         * The {@code WITH CHECK} clause, for a {@code write} policy: what the row
         * may look like <em>after</em> the save.
         *
         * <p>Null means "the same as {@code scope}", which is Postgres's default
         * and the safe one. Send one explicitly when the condition is about
         * workflow state rather than ownership: "you may edit drafts" must not
         * also mean "and never publish one".
         *
         * <p>Ignored for {@code read} and {@code delete}, which leave no new
         * state to check.
         */
        JsonNode checkScope,
        Boolean enabled) {
}
