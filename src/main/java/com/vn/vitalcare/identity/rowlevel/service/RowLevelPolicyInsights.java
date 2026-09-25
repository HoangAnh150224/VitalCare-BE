package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.rowlevel.dto.CoverageResponse;
import com.vn.vitalcare.identity.rowlevel.dto.ExplainResponse;
import com.vn.vitalcare.identity.rowlevel.dto.PolicyMetadataResponse;
import com.vn.vitalcare.identity.rowlevel.dto.SimulateRequest;
import com.vn.vitalcare.identity.rowlevel.dto.SimulateResponse;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.Op;
import com.vn.vitalcare.share.security.rowlevel.PolicyValidationException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import com.vn.vitalcare.share.security.rowlevel.Tri;
import com.vn.vitalcare.share.web.ListParams;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The four read-only endpoints that make this feature operable rather than
 * merely correct.
 *
 * <p>{@code /metadata} is what lets the admin screen have no expression box at
 * all. {@code /explain} answers "which rule is doing this". {@code /simulate}
 * answers "why can this person not see that record", and answers it in three
 * values, because the answer is so often {@code UNKNOWN} and {@code UNKNOWN} is
 * the one nobody guesses. {@code /coverage} turns a missing policy from an
 * invisible default into a red cell.
 *
 * <p>{@code /explain} and {@code /simulate} read a named account and a named
 * row, so their endpoints require {@code users:read} on top of
 * {@code row_level_policies:read} — simulating somebody else's access is reading
 * about somebody else.
 */
@Service
@Transactional(readOnly = true)
public class RowLevelPolicyInsights {

    private final RowLevelRegistry registry;

    // The implementation rather than the facade, because these endpoints need
    // the assembled EffectiveScope -- which is an internal of this slice and has
    // no business being on the interface every domain calls.
    private final RowLevelSecurityImpl security;
    private final RowLevelPrincipalResolver principals;
    private final EntityEvaluator evaluator;
    private final RoleService roleService;
    private final EntityManager entityManager;

    public RowLevelPolicyInsights(RowLevelRegistry registry,
                                  RowLevelSecurityImpl security,
                                  RowLevelPrincipalResolver principals,
                                  EntityEvaluator evaluator,
                                  RoleService roleService,
                                  EntityManager entityManager) {
        this.registry = registry;
        this.security = security;
        this.principals = principals;
        this.evaluator = evaluator;
        this.roleService = roleService;
        this.entityManager = entityManager;
    }

    /** What a policy on this resource may say. */
    public PolicyMetadataResponse metadata(String resource) {
        RowLevelRegistry.Managed managed = resource(resource);
        RowLevelPolicySet<Object> policySet = managed.typedPolicySet();

        List<PolicyMetadataResponse.Field> fields = managed.fields().values().stream()
                .map(RowLevelPolicyInsights::describe)
                .toList();

        List<PolicyMetadataResponse.Context> context = registry.contextProviders().stream()
                .map(provider -> new PolicyMetadataResponse.Context(
                        provider.key(), wireType(provider.type()), provider.isCollection()))
                .toList();

        List<PolicyMetadataResponse.DesignTime> designTime = new ArrayList<>();
        policySet.scopes().forEach(scope -> designTime.add(new PolicyMetadataResponse.DesignTime(
                "SCOPE", scope.action().code(), scope.roleCode(), scope.name())));
        policySet.filters().forEach(filter -> designTime.add(new PolicyMetadataResponse.DesignTime(
                "FILTER", filter.action().code(), null, filter.name())));

        return new PolicyMetadataResponse(
                managed.resource(),
                policySet.defaultScope().name(),
                fields,
                context,
                List.copyOf(policySet.notCheckSafe()),
                List.copyOf(designTime));
    }

    /** Which rules apply to one account, and what they add up to. */
    public ExplainResponse explain(String resource, String action, long userId) {
        RowLevelRegistry.Managed managed = resource(resource);
        Action parsed = action(action);
        RowLevelPrincipal principal = principal(userId);

        EffectiveScope effective = security.effectiveScope(managed, parsed);
        List<EffectiveScope.Contribution> applicable = security.applicableScopes(effective, principal);

        // A runtime scope is keyed by role id, which is what the cache matches
        // on and what nobody reading an explanation wants to see. Resolved to
        // codes here, once, rather than being carried around as codes and
        // matched against something the database does not key by.
        Map<String, String> roleCodes = roleCodes();

        List<ExplainResponse.Rule> scopes = effective.scopes().stream()
                .map(scope -> rule(scope, applicable.contains(scope), roleCodes))
                .toList();
        List<ExplainResponse.Rule> filters = effective.filters().stream()
                // A filter has no role to hold, so it always applies. That is
                // the whole point of it not naming one.
                .map(filter -> rule(filter, true, roleCodes))
                .toList();

        return new ExplainResponse(
                new ExplainResponse.Principal(
                        principal.userId(),
                        principal.organizationId(),
                        principal.departmentId(),
                        List.copyOf(principal.roleCodes())),
                managed.resource(),
                parsed.code(),
                effective.defaultScope().name(),
                scopes,
                filters,
                effectiveReading(effective, applicable),
                warnings(managed, effective, principal, parsed));
    }

    /**
     * Whether one account could do one thing to one row, and why not.
     *
     * <p>Reads the row directly through the {@code EntityManager} rather than
     * through a scope, on purpose: the question is what the <em>policies</em> say
     * about it, and filtering it out before asking would make the tool answer
     * "no such row" to every case it exists to explain.
     */
    public SimulateResponse simulate(SimulateRequest request) {
        RowLevelRegistry.Managed managed = resource(request.resource());
        Action action = action(request.action());
        RowLevelPrincipal principal = principal(request.userId());

        Object entity = entityManager.find(managed.entityClass(), request.recordId());
        if (entity == null) {
            throw new ResourceNotFoundException(managed.resource(), request.recordId());
        }

        EffectiveScope effective = security.effectiveScope(managed, action);
        Tri result = security.evaluate(effective, entity, principal);

        if (result.isTrue()) {
            return new SimulateResponse(true, result.name(), null, null);
        }

        Optional<ScopePlan.Leaf> failure = firstFailure(effective, principal, entity);
        return new SimulateResponse(
                false,
                result.name(),
                failure.map(leaf -> leaf.field().policyPath() + " " + leaf.op().code()).orElse(null),
                because(failure, entity, result));
    }

    /** The grid of which roles have a scope where. */
    public CoverageResponse coverage() {
        // Every role, in one page. The grid is roles x actions and both are
        // small; paging it would only mean a matrix with holes in it.
        MultiValueMap<String, String> everyRole = new LinkedMultiValueMap<>();
        everyRole.add("_start", "0");
        everyRole.add("_end", "1000");
        List<Role> roles = roleService.list(new ListParams(everyRole)).getContent();

        List<CoverageResponse.Resource> resources = new ArrayList<>();
        for (RowLevelRegistry.Managed managed : registry.all()) {
            DefaultScope defaultScope = managed.policySet().defaultScope();

            List<CoverageResponse.Cell> cells = new ArrayList<>();
            for (Role role : roles) {
                for (Action action : Action.values()) {
                    EffectiveScope effective = security.effectiveScope(managed, action);
                    boolean hasScope = effective.scopes().stream().anyMatch(scope ->
                            scope.isRuntime()
                                    ? String.valueOf(role.getId()).equals(scope.role())
                                    : role.getCode().equals(scope.role()));

                    cells.add(new CoverageResponse.Cell(
                            role.getCode(),
                            action.code(),
                            hasScope,
                            hasScope ? "OK" : defaultScope == DefaultScope.FULL ? "UNRESTRICTED" : "CLOSED"));
                }
            }
            resources.add(new CoverageResponse.Resource(
                    managed.resource(), defaultScope.name(), List.copyOf(cells)));
        }
        return new CoverageResponse(List.copyOf(resources));
    }

    private Optional<ScopePlan.Leaf> firstFailure(
            EffectiveScope effective, RowLevelPrincipal principal, Object entity) {

        for (EffectiveScope.Contribution scope : security.applicableScopes(effective, principal)) {
            if (scope.isRuntime()) {
                Optional<ScopePlan.Leaf> failure = evaluator.firstFailure(scope.plan(), entity, principal);
                if (failure.isPresent()) {
                    return failure;
                }
            }
        }
        for (EffectiveScope.Contribution filter : effective.filters()) {
            if (filter.isRuntime()) {
                Optional<ScopePlan.Leaf> failure = evaluator.firstFailure(filter.plan(), entity, principal);
                if (failure.isPresent()) {
                    return failure;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The reason in words.
     *
     * <p>Naming the null is the point. "createdBy is null" is the answer to most
     * {@code UNKNOWN}s, and without it somebody stares at a policy that looks
     * exactly right and at a row that looks exactly right.
     */
    private String because(Optional<ScopePlan.Leaf> failure, Object entity, Tri result) {
        if (failure.isEmpty()) {
            return result == Tri.FALSE
                    ? "no scope grants this account access to this row"
                    : "the policies could not be evaluated against this row";
        }

        FieldDescriptor field = failure.get().field();
        Object value = org.springframework.beans.PropertyAccessorFactory
                .forBeanPropertyAccess(entity)
                .getPropertyValue(field.attribute());

        if (value == null) {
            return "%s is null, so the condition is UNKNOWN — and UNKNOWN refuses the row"
                    .formatted(field.attribute());
        }
        return "%s does not satisfy the condition".formatted(field.policyPath());
    }

    private List<String> warnings(
            RowLevelRegistry.Managed managed,
            EffectiveScope effective,
            RowLevelPrincipal principal,
            Action action) {

        if (effective.defaultScope() != DefaultScope.FULL) {
            return List.of();
        }

        List<String> warnings = new ArrayList<>();
        for (String roleCode : principal.roleCodes()) {
            boolean covered = effective.scopes().stream().anyMatch(scope ->
                    scope.isRuntime()
                            ? principal.roleIds().contains(Long.valueOf(scope.role()))
                            : roleCode.equals(scope.role()));
            if (!covered) {
                warnings.add("role %s has no SCOPE for (%s, %s), so it is not narrowed — defaultScope is FULL"
                        .formatted(roleCode, managed.resource(), action.code()));
            }
        }
        if (effective.blocked()) {
            warnings.add("a FILTER on this resource is quarantined, so everything is refused until it is fixed");
        }
        return List.copyOf(warnings);
    }

    private static String effectiveReading(
            EffectiveScope effective, List<EffectiveScope.Contribution> applicable) {

        if (effective.blocked()) {
            return "nothing (a FILTER is quarantined)";
        }

        String visible = applicable.isEmpty()
                ? effective.defaultScope() == DefaultScope.FULL ? "everything" : "nothing"
                : applicable.stream()
                        .map(EffectiveScope.Contribution::describe)
                        .reduce((a, b) -> a + " OR " + b)
                        .map(text -> "(" + text + ")")
                        .orElse("nothing");

        if (effective.filters().isEmpty()) {
            return visible;
        }
        return effective.filters().stream()
                .map(EffectiveScope.Contribution::describe)
                .reduce(visible, (a, b) -> a + " AND " + b);
    }

    private static ExplainResponse.Rule rule(
            EffectiveScope.Contribution contribution,
            boolean applies,
            Map<String, String> roleCodes) {

        return new ExplainResponse.Rule(
                contribution.source(),
                // A design-time scope already names a code; a runtime one names
                // an id, and falls back to it if the role has since been
                // deleted -- which the CASCADE makes unlikely but not
                // impossible within a window.
                contribution.role() == null
                        ? null
                        : roleCodes.getOrDefault(contribution.role(), contribution.role()),
                contribution.name(),
                contribution.describe(),
                // Only when it says something the using clause does not --
                // repeating an identical condition twice reads like a mistake.
                contribution.hasDistinctCheck() ? contribution.describeCheck() : null,
                applies);
    }

    /** Role ids as strings, mapped to their codes, for rendering a rule. */
    private Map<String, String> roleCodes() {
        MultiValueMap<String, String> everyRole = new LinkedMultiValueMap<>();
        everyRole.add("_start", "0");
        everyRole.add("_end", "1000");

        Map<String, String> codes = new LinkedHashMap<>();
        for (Role role : roleService.list(new ListParams(everyRole)).getContent()) {
            codes.put(String.valueOf(role.getId()), role.getCode());
        }
        return codes;
    }

    private RowLevelRegistry.Managed resource(String name) {
        return registry.byResource(name).orElseThrow(() -> new PolicyValidationException(
                "resource", "\"%s\" is not under row-level management".formatted(name)));
    }

    private static Action action(String value) {
        try {
            return Action.from(value);
        } catch (IllegalArgumentException e) {
            throw new PolicyValidationException("action", "must be read, write or delete");
        }
    }

    private RowLevelPrincipal principal(long userId) {
        return principals.forUser(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private static PolicyMetadataResponse.Field describe(FieldDescriptor field) {
        Class<?> type = field.comparableType();

        List<String> values = List.of();
        if (type.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            values = List.copyOf(names);
        }

        return new PolicyMetadataResponse.Field(
                field.policyPath(),
                wireType(type),
                field.nullable(),
                values,
                field.operators().stream().map(Op::code).sorted().toList(),
                // Three-valued logic makes not(x = v) refuse a row where x is
                // null. Warning at the moment the `not` is written, and offering
                // the isNull branch beside it, is far better than letting the
                // list and the save disagree about it later.
                field.nullable());
    }

    /**
     * A type name the builder can render an input from, rather than a Java class
     * name it would have to know about.
     */
    private static String wireType(Class<?> type) {
        if (type.isEnum()) {
            return "enum";
        }
        if (type == Long.class || type == Integer.class) {
            return "long";
        }
        if (type == Double.class || type == BigDecimal.class) {
            return "number";
        }
        if (type == Boolean.class) {
            return "boolean";
        }
        if (type == LocalDate.class) {
            return "date";
        }
        if (type == Instant.class || type == LocalDateTime.class) {
            return "datetime";
        }
        if (CharSequence.class.isAssignableFrom(type)) {
            return "string";
        }
        return type.getSimpleName().toLowerCase(Locale.ROOT);
    }
}
