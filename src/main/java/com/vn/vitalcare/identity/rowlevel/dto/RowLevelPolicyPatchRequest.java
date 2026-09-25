package com.vn.vitalcare.identity.rowlevel.dto;

import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * A partial update. Every field is optional and {@code null} means "leave it
 * alone", as everywhere else in this API.
 *
 * <p>{@code kind} and {@code resource} are deliberately absent. Changing either
 * turns a policy into a different policy about different rows, and doing that in
 * place would leave an audit trail claiming one thing was edited when in truth
 * one rule was withdrawn and another introduced. Delete it and write the new one.
 */
public record RowLevelPolicyPatchRequest(
        RoleRef role,
        @Size(max = 16) String action,
        @Size(max = 160) String name,
        @Size(max = 64) String policyGroup,
        @Size(max = 500) String description,
        JsonNode scope,
        /**
         * The {@code WITH CHECK} clause, for a {@code write} policy: what the row
         * may look like <em>after</em> the save.
         *
         * <p>Null means "the same as {@code scope}", which is Postgres's default
         * and the safe one.
         *
         * <p>Ignored for {@code read} and {@code delete}, which leave no new
         * state to check.
         */
        JsonNode checkScope,
        Boolean enabled) {
}
