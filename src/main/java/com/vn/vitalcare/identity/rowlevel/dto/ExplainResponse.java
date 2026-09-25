package com.vn.vitalcare.identity.rowlevel.dto;

import java.util.List;

/**
 * Why one account sees what it sees.
 *
 * <p>Answers the question every row-level system is asked within a week of
 * shipping, and which is otherwise an afternoon of reading logs: <em>which rule
 * is doing this?</em>
 *
 * @param warnings the gaps, said out loud. A role with no scope on a resource
 *                 whose default is {@code FULL} is not narrowed at all, and the
 *                 whole point of choosing {@code FULL} is that this stays
 *                 visible instead of being discovered later
 */
public record ExplainResponse(
        Principal principal,
        String resource,
        String action,
        String defaultScope,
        List<Rule> scopes,
        List<Rule> filters,
        String effective,
        List<String> warnings) {

    public record Principal(
            long userId,
            Long organizationId,
            Long departmentId,
            List<String> roleCodes) {
    }

    /**
     * @param source {@code runtime#11} or {@code code:PatientRowLevelPolicies} —
     *               where the rule lives, which is the first thing anybody asks
     *               after seeing one
     * @param reads  the rule as it will actually run, after normalisation
     */
    public record Rule(
            String source,
            String role,
            String name,
            String reads,
            /**
             * The {@code WITH CHECK} half, when it differs from {@code reads}.
             * Null when the two clauses are the same, which is the usual case
             * and the safe one.
             */
            String checks,
            boolean applies) {
    }
}
