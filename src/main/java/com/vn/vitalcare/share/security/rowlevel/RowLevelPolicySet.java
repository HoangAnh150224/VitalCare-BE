package com.vn.vitalcare.share.security.rowlevel;

import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;

/**
 * What a feature declares about its own row-level security.
 *
 * <p>One bean per {@link RowLevelResource}, living in that feature's own slice —
 * {@code module/bpm/task/security/TaskRowLevelPolicies} for {@code tasks}. It
 * implements an interface from {@code shared/}, so the feature slice never has
 * to know the policy engine exists; the engine collects the beans and the
 * dependency runs one way.
 *
 * <h2>Design-time and runtime</h2>
 *
 * <p>Two layers, and the split is what resolves "policies must be expressive"
 * against "policies must not be an execution surface".
 *
 * <p>What is declared <em>here</em> is source code: reviewed, tested, deployed
 * with whatever depends on it, and able to say anything a
 * {@code Specification} can say — a subquery, a join, a call into another bean.
 * It cannot be edited or switched off from the admin screen, which is the point
 * for a business invariant.
 *
 * <p>What is stored in {@code row_level_policy} is data: a {@link ScopeTree}
 * over a closed vocabulary, editable at runtime by somebody with the right
 * permission, audited, and disablable. It can express far less, and it needs no
 * sandbox because there is nothing to sandbox.
 *
 * @param <T> the entity this set is about
 */
public interface RowLevelPolicySet<T> {

    /** The resource name, matching this feature's {@link RowLevelResource} value exactly. */
    String resource();

    /** The entity class the policies apply to. */
    Class<T> entityClass();

    /**
     * What a role with no {@code SCOPE} of its own may reach.
     *
     * <p>Mandatory, with no implicit default: it is a decision to be made when a
     * resource is brought under management, not something to inherit by
     * accident. See {@link DefaultScope} for which to pick.
     */
    DefaultScope defaultScope();

    /**
     * Every field a runtime policy may mention. Closed — anything else is
     * refused when the policy is saved.
     */
    List<FieldDescriptor> fields();

    /**
     * Fields a {@code write} policy must not mention, because their value only
     * exists after the row has been flushed.
     *
     * <p>Refused at the moment a policy naming one is <em>saved</em>, with the
     * reason spelled out — rather than left to be discovered later as a create
     * that is rejected for no visible cause. A policy such as
     * {@code createdAt >= today} would turn down every new record, because at
     * the time the check runs on a create there is no {@code createdAt} yet.
     */
    default Set<String> notCheckSafe() {
        return Set.of();
    }

    /**
     * Filters declared in source: conditions nobody may escape, whatever roles
     * they hold.
     *
     * <p>Shown on the admin screen read-only and labelled as coming from the
     * code, so that an administrator sees the <em>whole</em> policy in force
     * rather than only the half they can edit.
     */
    default List<DesignTimeFilter<T>> filters() {
        return List.of();
    }

    /** Scopes declared in source, for a rule that should not be switchable from a screen. */
    default List<DesignTimeScope<T>> scopes() {
        return List.of();
    }

    /**
     * A source-declared scope, attached to one role.
     *
     * <p>Counts as that role having a scope, so a resource covered entirely by
     * design-time policies shows no red cells on the coverage matrix and can be
     * moved to {@link DefaultScope#NONE}.
     *
     * @param roleCode the role this widens, by code rather than by id — source
     *                 code cannot reference a database identity
     * @param using    the {@code WHERE} half
     * @param check    the same condition in memory, for {@code WITH CHECK} and
     *                 the row flags. Written by hand; see {@link TriPredicate}
     */
    record DesignTimeScope<T>(
            String roleCode,
            Action action,
            String name,
            Specification<T> using,
            TriPredicate<T> check) {
    }

    /**
     * A source-declared filter, attached to no role and therefore inescapable.
     *
     * <p>Attaching a prohibition to a role would let somebody step around it by
     * simply not holding that role — and a role created tomorrow would not have
     * it at all.
     */
    record DesignTimeFilter<T>(
            Action action,
            String name,
            Specification<T> using,
            TriPredicate<T> check) {
    }
}
