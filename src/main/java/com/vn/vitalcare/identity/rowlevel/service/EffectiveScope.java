package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import java.util.List;

/**
 * Everything that applies to one caller, one resource and one action, assembled.
 *
 * <p>The algebra it stands for is the whole feature in two lines:
 *
 * <pre>
 *   VISIBLE = ( OR over the SCOPEs of the roles the caller holds )
 *           AND ( AND over every FILTER on the resource )
 * </pre>
 *
 * <p>with the first half falling back to {@link DefaultScope} when the caller
 * holds no role that has a scope here, and the second half being {@code TRUE}
 * when there are no filters.
 *
 * <p>There is no priority and no ordering. An authorisation algorithm that
 * depends on the order its rules were written in is one nobody can verify by
 * reading it. A {@code FILTER} always wins because intersection always wins —
 * not because it sorted first.
 *
 * @param blocked true when a broken {@code FILTER} has closed this pair
 *                entirely; nothing else in this record is then consulted
 */
public record EffectiveScope(
        String resource,
        DefaultScope defaultScope,
        List<Contribution> scopes,
        List<Contribution> filters,
        boolean blocked) {

    public EffectiveScope {
        scopes = List.copyOf(scopes);
        filters = List.copyOf(filters);
    }

    /**
     * One policy that applies, whichever layer it came from.
     *
     * @param source     {@code runtime#11} or {@code code:PatientRowLevelPolicies},
     *                   so {@code /explain} can say where a rule lives — which
     *                   is the first thing anybody asks after seeing one
     * @param role       the role a {@code SCOPE} belongs to, null for a filter
     * @param plan       the compiled {@code USING} tree, for a runtime policy
     * @param checkPlan  the compiled {@code WITH CHECK} tree. Equal to
     *                   {@code plan} unless the policy declared its own — which
     *                   is exactly the shape a design-time policy has always
     *                   had, since {@link DesignTime} carries the two halves
     *                   separately. This brings runtime policies into line
     * @param designTime the hand-written pair, for a source-declared policy
     */
    public record Contribution(
            String source,
            String role,
            String name,
            ScopePlan plan,
            ScopePlan checkPlan,
            DesignTime designTime) {

        public boolean isRuntime() {
            return plan != null;
        }

        /** How the {@code USING} half reads, for {@code /explain} and the audit trail. */
        public String describe() {
            return plan != null ? plan.describe() : name;
        }

        /** How the {@code WITH CHECK} half reads. */
        public String describeCheck() {
            return checkPlan != null ? checkPlan.describe() : name;
        }

        /** True when the two clauses differ, which is worth pointing out in an explanation. */
        public boolean hasDistinctCheck() {
            return plan != null && checkPlan != null && !plan.describe().equals(checkPlan.describe());
        }
    }

    /**
     * A source-declared policy's two halves, kept together.
     *
     * <p>Both are needed and neither can be generated from the other: a
     * design-time policy is arbitrary Java, which is exactly the expressive
     * power that makes it uncompilable. That is the price of writing one by
     * hand, and it is why every one of them owes a cross-check test that the
     * runtime policies get for free.
     */
    public record DesignTime(
            org.springframework.data.jpa.domain.Specification<Object> using,
            com.vn.vitalcare.share.security.rowlevel.TriPredicate<Object> check) {
    }
}
