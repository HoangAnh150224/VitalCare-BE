package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.identity.rowlevel.entity.PolicyKind;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import com.vn.vitalcare.share.security.rowlevel.RowLevelViolationException;
import com.vn.vitalcare.share.security.rowlevel.Tri;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * The facade, and the only place the scope algebra is actually applied.
 *
 * <p>Everything else in the slice compiles, caches or evaluates. This is where
 * scopes are unioned, filters are intersected, the default is applied when a
 * role has nothing of its own, and the answer is handed back as either a
 * {@link Specification} for SQL or a {@link Tri} for an entity in hand.
 */
@Service
public class RowLevelSecurityImpl implements RowLevelSecurity {

    private static final Logger log = LoggerFactory.getLogger(RowLevelSecurityImpl.class);

    /**
     * Set only inside {@link #asSystem}.
     *
     * <p>A thread local rather than a parameter threaded through every service
     * signature, for the same reason {@code CurrentUser} is a static read of the
     * security context. It is always cleared in a {@code finally}, so a pooled
     * thread cannot inherit somebody's bypass.
     */
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final RowLevelRegistry registry;
    private final RowLevelPolicyCache cache;
    private final RowLevelPrincipalResolver principals;
    private final PredicateCompiler predicates;
    private final EntityEvaluator evaluator;
    private final ObjectProvider<MeterRegistry> meters;

    public RowLevelSecurityImpl(RowLevelRegistry registry,
                                RowLevelPolicyCache cache,
                                RowLevelPrincipalResolver principals,
                                PredicateCompiler predicates,
                                EntityEvaluator evaluator,
                                ObjectProvider<MeterRegistry> meters) {
        this.registry = registry;
        this.cache = cache;
        this.principals = principals;
        this.predicates = predicates;
        this.evaluator = evaluator;
        this.meters = meters;
    }

    @Override
    public <T> Specification<T> scope(Class<T> entityClass, Action action) {
        Optional<RowLevelRegistry.Managed> managed = registry.byEntity(entityClass);
        if (managed.isEmpty() || BYPASS.get()) {
            return unrestricted();
        }

        Optional<RowLevelPrincipal> principal = principals.current();
        if (principal.isEmpty()) {
            // Startup, scheduled work, the sign-in flow itself. No caller means
            // nothing to scope to -- and answering with an exception here would
            // make the authority cache's own load fail the moment `users` came
            // under management.
            return unrestricted();
        }

        EffectiveScope effective = effectiveScope(managed.get(), action);
        return specification(effective, principal.get());
    }

    @Override
    public <T> Specification<T> byId(Class<T> entityClass, Action action, Object id) {
        Specification<T> scope = scope(entityClass, action);
        return scope.and((root, query, cb) -> cb.equal(root.get("id"), id));
    }

    @Override
    public <T> Specification<T> byIds(Class<T> entityClass, Action action, Collection<?> ids) {
        Specification<T> scope = scope(entityClass, action);
        if (ids.isEmpty()) {
            return scope.and((root, query, cb) -> cb.disjunction());
        }
        return scope.and((root, query, cb) -> root.get("id").in(ids));
    }

    @Override
    public void checkWritable(Object entity) {
        Optional<RowLevelRegistry.Managed> managed = registry.byEntity(entity.getClass());
        if (managed.isEmpty() || BYPASS.get()) {
            return;
        }
        Optional<RowLevelPrincipal> principal = principals.current();
        if (principal.isEmpty()) {
            return;
        }

        EffectiveScope effective = effectiveScope(managed.get(), Action.WRITE);
        if (evaluate(effective, entity, principal.get(), Clause.CHECK).isTrue()) {
            return;
        }
        throw violation(effective, entity, principal.get());
    }

    @Override
    public Map<String, Boolean> capabilities(Object entity) {
        Optional<RowLevelRegistry.Managed> managed = registry.byEntity(entity.getClass());
        if (managed.isEmpty()) {
            return Map.of();
        }
        Optional<RowLevelPrincipal> principal = principals.current();
        if (principal.isEmpty()) {
            return Map.of();
        }

        // Fail fast rather than degrade. A lazy association the evaluator would
        // have to initialise turns one query into one per row, and a silent N+1
        // is far harder to notice than a missing flag.
        if (!isFullyLoaded(managed.get(), entity)) {
            log.warn("Not reporting row flags for {}: a policy association was not fetched",
                    managed.get().resource());
            return Map.of();
        }

        RowLevelPrincipal caller = principal.get();
        Map<String, Boolean> flags = new LinkedHashMap<>();
        EffectiveScope write = effectiveScope(managed.get(), Action.WRITE);
        // Both halves, against the state the row is in now: "you may open the
        // edit form, as long as you do not push it out of your scope". Reporting
        // USING alone would offer a button that always ends in a 422.
        flags.put("write", evaluate(write, entity, caller, Clause.USING).isTrue()
                && evaluate(write, entity, caller, Clause.CHECK).isTrue());
        flags.put("delete", evaluate(effectiveScope(managed.get(), Action.DELETE), entity, caller).isTrue());
        return Map.copyOf(flags);
    }

    @Override
    public boolean canRead(Object entity) {
        Optional<RowLevelRegistry.Managed> managed = registry.byEntity(entity.getClass());
        if (managed.isEmpty() || BYPASS.get()) {
            return true;
        }
        Optional<RowLevelPrincipal> principal = principals.current();
        if (principal.isEmpty()) {
            return true;
        }
        return evaluate(effectiveScope(managed.get(), Action.READ), entity, principal.get()).isTrue();
    }

    @Override
    public List<String> fetchHints(Class<?> entityClass) {
        return registry.byEntity(entityClass)
                .map(managed -> managed.fields().values().stream()
                        .filter(field -> field.kind() == FieldDescriptor.Kind.REFERENCE)
                        .map(FieldDescriptor::attribute)
                        .distinct()
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Which resources this caller is actually narrowed on.
     *
     * <p>Sent to the client so a list that comes back empty can say
     * <em>why</em>. Somebody with a broad permission and a narrow scope sees
     * exactly what a system with no data looks like, and that is a support
     * ticket waiting to be raised.
     */
    @Override
    public List<String> scopedResourcesForCurrentUser() {
        return scopedResources(principals.current());
    }

    @Override
    public List<String> scopedResourcesFor(long userId) {
        return scopedResources(principals.forUser(userId));
    }

    private List<String> scopedResources(Optional<RowLevelPrincipal> principal) {
        if (principal.isEmpty()) {
            return List.of();
        }

        List<String> scoped = new ArrayList<>();
        for (RowLevelRegistry.Managed managed : registry.all()) {
            EffectiveScope effective = effectiveScope(managed, Action.READ);
            if (isNarrowed(effective, principal.get())) {
                scoped.add(managed.resource());
            }
        }
        return List.copyOf(scoped);
    }

    @Override
    public <R> R asSystem(String taskName, Supplier<R> work) {
        boolean previous = BYPASS.get();
        // INFO, not DEBUG. In production a debug line is the same as no line at
        // all, and a request that skipped row-level filtering is the last thing
        // that should leave no trace.
        log.info("Row-level filtering bypassed for {}", taskName);
        meters.ifAvailable(registry -> registry.counter("rowlevel.bypass", "task", taskName).increment());
        BYPASS.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            BYPASS.set(previous);
        }
    }

    @Override
    public void asSystem(String taskName, Runnable work) {
        asSystem(taskName, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Assembles every rule in force for a resource and action.
     *
     * <p>Both layers land in the same two lists, so the algebra below does not
     * have to know which is which — a design-time scope widens exactly as a
     * runtime one does, and a design-time filter is just as inescapable.
     */
    EffectiveScope effectiveScope(RowLevelRegistry.Managed managed, Action action) {
        List<EffectiveScope.Contribution> scopes = new ArrayList<>();
        List<EffectiveScope.Contribution> filters = new ArrayList<>();

        for (RowLevelPolicyCache.CompiledPolicy policy : cache.policiesFor(managed.resource(), action)) {
            EffectiveScope.Contribution contribution = new EffectiveScope.Contribution(
                    "runtime#" + policy.policyId(),
                    policy.roleId() == null ? null : String.valueOf(policy.roleId()),
                    policy.name(),
                    policy.plan(),
                    policy.checkPlan(),
                    null);
            (policy.kind() == PolicyKind.FILTER ? filters : scopes).add(contribution);
        }

        RowLevelPolicySet<Object> policySet = managed.typedPolicySet();
        String source = "code:" + policySet.getClass().getSimpleName();

        for (RowLevelPolicySet.DesignTimeScope<Object> declared : policySet.scopes()) {
            if (declared.action() == action) {
                scopes.add(new EffectiveScope.Contribution(
                        source, declared.roleCode(), declared.name(), null, null,
                        new EffectiveScope.DesignTime(declared.using(), declared.check())));
            }
        }
        for (RowLevelPolicySet.DesignTimeFilter<Object> declared : policySet.filters()) {
            if (declared.action() == action) {
                filters.add(new EffectiveScope.Contribution(
                        source, null, declared.name(), null, null,
                        new EffectiveScope.DesignTime(declared.using(), declared.check())));
            }
        }

        return new EffectiveScope(
                managed.resource(),
                policySet.defaultScope(),
                scopes,
                filters,
                cache.isBlocked(managed.resource(), action));
    }

    /** The scopes this caller's roles actually contribute. Runtime ones match by id, design-time by code. */
    List<EffectiveScope.Contribution> applicableScopes(EffectiveScope effective, RowLevelPrincipal principal) {
        List<EffectiveScope.Contribution> applicable = new ArrayList<>();
        for (EffectiveScope.Contribution scope : effective.scopes()) {
            if (scope.role() == null) {
                continue;
            }
            boolean holds = scope.isRuntime()
                    ? principal.roleIds().contains(Long.valueOf(scope.role()))
                    : principal.roleCodes().contains(scope.role());
            if (holds) {
                applicable.add(scope);
            }
        }
        return applicable;
    }

    @SuppressWarnings("unchecked")
    private <T> Specification<T> specification(EffectiveScope effective, RowLevelPrincipal principal) {
        if (effective.blocked()) {
            // A quarantined FILTER. Everything is refused until it is fixed,
            // because a prohibition that stopped applying is a leak and losing
            // one rule quietly is worse than losing the screen loudly.
            return (root, query, cb) -> cb.disjunction();
        }

        List<EffectiveScope.Contribution> applicable = applicableScopes(effective, principal);

        Specification<T> visible = applicable.isEmpty()
                ? defaultSpecification(effective.defaultScope())
                : (root, query, cb) -> cb.or(applicable.stream()
                        .map(scope -> toPredicate(scope, root, query, cb, principal))
                        .toArray(Predicate[]::new));

        for (EffectiveScope.Contribution filter : effective.filters()) {
            Specification<T> and = (root, query, cb) -> toPredicate(filter, root, query, cb, principal);
            visible = visible.and(and);
        }
        return visible;
    }

    @SuppressWarnings("unchecked")
    private Predicate toPredicate(
            EffectiveScope.Contribution contribution,
            Root<?> root,
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            RowLevelPrincipal principal) {

        if (contribution.isRuntime()) {
            return predicates.toPredicate(contribution.plan(), root, query, cb, principal);
        }
        Predicate predicate = contribution.designTime().using()
                .toPredicate((Root<Object>) root, query, cb);
        // A design-time Specification is allowed to return null, the way
        // Spring Data's own do to mean "no restriction".
        return predicate == null ? cb.conjunction() : predicate;
    }

    /**
     * Which half of a write policy a question is about.
     *
     * <p>{@code USING} asks "may this row be touched", against the state it is
     * in. {@code CHECK} asks "may it be left looking like this", against the
     * state a save would produce. They are the same condition unless a policy
     * says otherwise, and separating them is what lets "you may edit drafts"
     * not also mean "and never publish one".
     */
    enum Clause {
        USING,
        CHECK
    }

    Tri evaluate(EffectiveScope effective, Object entity, RowLevelPrincipal principal) {
        return evaluate(effective, entity, principal, Clause.USING);
    }

    Tri evaluate(EffectiveScope effective, Object entity, RowLevelPrincipal principal, Clause clause) {
        if (effective.blocked()) {
            return Tri.FALSE;
        }

        List<EffectiveScope.Contribution> applicable = applicableScopes(effective, principal);

        Tri visible = applicable.isEmpty()
                ? defaultTri(effective.defaultScope())
                : applicable.stream()
                        .map(scope -> evaluate(scope, entity, principal, clause))
                        .reduce(Tri.FALSE, Tri::or);

        for (EffectiveScope.Contribution filter : effective.filters()) {
            visible = visible.and(evaluate(filter, entity, principal, clause));
        }
        return visible;
    }

    private Tri evaluate(
            EffectiveScope.Contribution contribution,
            Object entity,
            RowLevelPrincipal principal,
            Clause clause) {

        if (!contribution.isRuntime()) {
            // A design-time policy has always carried its two halves separately;
            // its Tri predicate is the check half, and its Specification is the
            // using half, which cannot be evaluated in memory. Asking it either
            // question gets the same answer it always gave.
            return contribution.designTime().check().test(entity, principal);
        }
        ScopePlan plan = clause == Clause.CHECK ? contribution.checkPlan() : contribution.plan();
        return evaluator.evaluate(plan, entity, principal);
    }

    /**
     * The field to name in a refusal.
     *
     * <p>Taken from the scope that came closest — the caller's own role's rule —
     * rather than from a filter, because the field a person can actually change
     * is the useful thing to point at.
     */
    private RowLevelViolationException violation(
            EffectiveScope effective, Object entity, RowLevelPrincipal principal) {

        Optional<ScopePlan.Leaf> blamed = blame(effective, entity, principal);
        if (blamed.isEmpty()) {
            return new RowLevelViolationException(null, null);
        }

        // The value the writer just chose, echoed back. Naming the field alone
        // says a change was refused; naming the value says which change, which
        // is the difference between "why?" and "oh, that one".
        FieldDescriptor field = blamed.get().field();
        Object rejected = org.springframework.beans.PropertyAccessorFactory
                .forBeanPropertyAccess(entity)
                .getPropertyValue(field.attribute());

        return new RowLevelViolationException(field.attribute(), describe(rejected));
    }

    /**
     * The leaf to name in a refusal.
     *
     * <p>Taken from the scope belonging to the caller's own role first, rather
     * than from a filter, because the field a person can actually change is the
     * useful thing to point at.
     */
    private Optional<ScopePlan.Leaf> blame(
            EffectiveScope effective, Object entity, RowLevelPrincipal principal) {

        for (EffectiveScope.Contribution scope : applicableScopes(effective, principal)) {
            if (scope.isRuntime()) {
                Optional<ScopePlan.Leaf> failure =
                        evaluator.firstFailure(scope.checkPlan(), entity, principal);
                if (failure.isPresent()) {
                    return failure;
                }
            }
        }
        for (EffectiveScope.Contribution filter : effective.filters()) {
            if (filter.isRuntime()
                    && !evaluate(filter, entity, principal, Clause.CHECK).isTrue()) {
                Optional<ScopePlan.Leaf> failure =
                        evaluator.firstFailure(filter.checkPlan(), entity, principal);
                if (failure.isPresent()) {
                    return failure;
                }
            }
        }
        return Optional.empty();
    }

    /** A value as somebody would recognise it on the form they just filled in. */
    private static String describe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        // A reference reads as its id, which is what the policy compares and
        // what the form's picker submitted.
        Object id = org.springframework.beans.PropertyAccessorFactory
                .forBeanPropertyAccess(value)
                .isReadableProperty("id")
                ? org.springframework.beans.PropertyAccessorFactory
                        .forBeanPropertyAccess(value).getPropertyValue("id")
                : null;
        return String.valueOf(id != null ? id : value);
    }

    /**
     * Whether this caller is genuinely narrowed here — which is not the same as
     * "there is a policy". A scope of {@code {"all": []}} is a policy that
     * restricts nothing, and telling somebody their empty list might be a
     * permissions problem when it is not would send them to ask a question with
     * no answer.
     */
    private boolean isNarrowed(EffectiveScope effective, RowLevelPrincipal principal) {
        if (effective.blocked()) {
            return true;
        }
        if (!effective.filters().isEmpty()) {
            return true;
        }

        List<EffectiveScope.Contribution> applicable = applicableScopes(effective, principal);
        if (applicable.isEmpty()) {
            return effective.defaultScope() == DefaultScope.NONE;
        }
        return applicable.stream().allMatch(scope -> !scope.isRuntime() || !scope.plan().isUnrestricted());
    }

    /**
     * Every association a policy on this resource might read, actually loaded.
     *
     * <p>Checked rather than assumed, because the cost of being wrong is a query
     * per row on a path that runs for every row.
     */
    private boolean isFullyLoaded(RowLevelRegistry.Managed managed, Object entity) {
        // The same list fetchHints reports, so what a domain is told to fetch
        // and what is checked here cannot drift apart.
        for (String association : fetchHints(managed.entityClass())) {
            Object value = org.springframework.beans.PropertyAccessorFactory
                    .forBeanPropertyAccess(entity)
                    .getPropertyValue(association);
            if (value != null && !Hibernate.isInitialized(value)) {
                return false;
            }
        }
        return true;
    }

    private static <T> Specification<T> defaultSpecification(DefaultScope defaultScope) {
        return defaultScope == DefaultScope.FULL
                ? (root, query, cb) -> cb.conjunction()
                : (root, query, cb) -> cb.disjunction();
    }

    private static Tri defaultTri(DefaultScope defaultScope) {
        return defaultScope == DefaultScope.FULL ? Tri.TRUE : Tri.FALSE;
    }

    private static <T> Specification<T> unrestricted() {
        return (root, query, cb) -> cb.conjunction();
    }
}
