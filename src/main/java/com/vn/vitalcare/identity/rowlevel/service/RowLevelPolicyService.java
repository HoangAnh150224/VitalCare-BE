package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.rowlevel.dto.RoleRef;
import com.vn.vitalcare.identity.rowlevel.dto.RowLevelPolicyPatchRequest;
import com.vn.vitalcare.identity.rowlevel.dto.RowLevelPolicyRequest;
import com.vn.vitalcare.identity.rowlevel.dto.RowLevelPolicyResponse;
import com.vn.vitalcare.identity.rowlevel.entity.PolicyKind;
import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicy;
import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicyAudit;
import com.vn.vitalcare.identity.rowlevel.repository.RowLevelPolicyAuditRepository;
import com.vn.vitalcare.identity.rowlevel.repository.RowLevelPolicyRepository;
import com.vn.vitalcare.identity.rowlevel.repository.RowLevelPolicySpecifications;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.PolicyValidationException;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPoliciesChanged;
import com.vn.vitalcare.share.security.rowlevel.ScopeTree;
import com.vn.vitalcare.share.web.ListParams;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Writing policies down: the ordinary CRUD half of the feature, plus the two
 * things that make it safe to expose to a person.
 *
 * <p><b>Every write is compiled before it is stored.</b> A tree that will not
 * compile is refused with a {@code 422} naming the node at fault, and that
 * refusal is what keeps the quarantine path at startup a rarity rather than a
 * routine occurrence: policies only break later because the <em>schema</em>
 * moved, never because somebody typed something impossible.
 *
 * <p><b>Every write is recorded.</b> The audit table has no foreign key to the
 * policy table, so the trail of a deleted policy survives it — which is the
 * trail most worth having.
 */
@Service
@Transactional(readOnly = true)
public class RowLevelPolicyService {

    /** Sort properties the list endpoint accepts; anything else is ignored. */
    private static final Set<String> SORTABLE =
            Set.of("id", "kind", "resource", "action", "name", "policyGroup", "enabled", "createdAt", "updatedAt");

    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.asc("resource"), Sort.Order.asc("action"), Sort.Order.asc("id"));

    private final RowLevelPolicyRepository repository;
    private final RowLevelPolicyAuditRepository auditRepository;
    private final RowLevelRegistry registry;
    private final PolicyCompiler compiler;
    private final ScopeTreeCodec codec;
    private final ObjectMapper mapper;

    // Both reached through their own domain's service rather than its
    // repository, which also means an unknown id produces the same 404 that
    // resource's own endpoints would.
    private final RoleService roleService;
    private final UserService userService;

    // Announces that the policy set moved. An event rather than a call into the
    // cache, for the same reason AuthoritiesChanged is one: the cache is the
    // thing that has to react, and a direct dependency each way is a cycle.
    private final ApplicationEventPublisher events;

    public RowLevelPolicyService(RowLevelPolicyRepository repository,
                                 RowLevelPolicyAuditRepository auditRepository,
                                 RowLevelRegistry registry,
                                 PolicyCompiler compiler,
                                 ScopeTreeCodec codec,
                                 ObjectMapper mapper,
                                 RoleService roleService,
                                 UserService userService,
                                 ApplicationEventPublisher events) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.registry = registry;
        this.compiler = compiler;
        this.codec = codec;
        this.mapper = mapper;
        this.roleService = roleService;
        this.userService = userService;
        this.events = events;
    }

    public Page<RowLevelPolicy> list(ListParams params) {
        return repository.findAll(
                RowLevelPolicySpecifications.from(params),
                params.pageable(SORTABLE, DEFAULT_SORT));
    }

    public RowLevelPolicy get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Row-level policy", id));
    }

    public List<RowLevelPolicy> getMany(List<Long> ids) {
        return repository.findAllById(ids);
    }

    @Transactional
    public RowLevelPolicy create(RowLevelPolicyRequest request) {
        PolicyKind kind = kind(request.kind());
        Action action = action(request.action());
        RowLevelRegistry.Managed resource = resource(request.resource());
        Role role = role(kind, request.role());

        // Compiled before it is stored. Refusing here is the whole difference
        // between "policies are data an administrator edits" and "policies are
        // data an administrator can break the application with".
        ScopeTree tree = codec.parse(request.scope());
        compiler.compile(resource, action, tree);

        // The check clause, or the using clause standing in for it. Compiled
        // either way, so a field that only exists after the flush is caught
        // whichever tree ends up governing the saved state.
        ScopeTree checkTree = request.checkScope() == null ? null : codec.parse(request.checkScope());
        compiler.compileCheck(resource, action, checkTree == null ? tree : checkTree);

        RowLevelPolicy policy = new RowLevelPolicy(
                kind, role, resource.resource(), action.code(), request.name().trim(), codec.write(tree));
        policy.setCheckScope(checkTree == null ? null : codec.write(checkTree));
        policy.setPolicyGroup(trimToNull(request.policyGroup()));
        policy.setDescription(trimToNull(request.description()));
        policy.setEnabled(request.enabled() == null || request.enabled());
        policy.setCreatedBy(currentUser());
        policy.setUpdatedBy(policy.getCreatedBy());

        RowLevelPolicy saved = repository.save(policy);
        record(saved, RowLevelPolicyAudit.Operation.CREATE, null, snapshot(saved));
        announce();
        return saved;
    }

    @Transactional
    public RowLevelPolicy update(Long id, RowLevelPolicyPatchRequest request) {
        RowLevelPolicy policy = get(id);
        String before = snapshot(policy);
        boolean wasEnabled = policy.isEnabled();

        RowLevelRegistry.Managed resource = resource(policy.getResource());
        Action action = request.action() == null ? action(policy.getAction()) : action(request.action());

        if (request.role() != null) {
            policy.setRole(role(policy.getKind(), request.role()));
        }
        if (request.action() != null) {
            policy.setAction(action.code());
        }
        if (request.name() != null) {
            policy.setName(request.name().trim());
        }
        if (request.policyGroup() != null) {
            policy.setPolicyGroup(trimToNull(request.policyGroup()));
        }
        if (request.description() != null) {
            policy.setDescription(trimToNull(request.description()));
        }
        if (request.scope() != null) {
            ScopeTree tree = codec.parse(request.scope());
            compiler.compile(resource, action, tree);
            policy.setScope(codec.write(tree));
        }
        if (request.checkScope() != null) {
            // An empty tree clears the override and goes back to "same as
            // USING"; anything else becomes the check clause in its own right.
            ScopeTree checkTree = codec.parse(request.checkScope());
            compiler.compileCheck(resource, action, checkTree);
            policy.setCheckScope(codec.write(checkTree));
        }
        if (request.scope() != null || request.checkScope() != null) {
            // Whichever half moved, the effective check has to still compile:
            // a new using clause becomes the check clause when there is no
            // override, and a cleared override falls back to it.
            compiler.compileCheck(resource, action, codec.parse(policy.effectiveCheckScope()));
        }
        if (request.scope() == null && request.checkScope() == null && request.action() != null) {
            // Neither tree changed but the action did, and notCheckSafe only
            // applies to a write policy's check clause. Re-compiling is what
            // stops a read policy on `createdAt` being turned into a write
            // policy that refuses every create.
            compiler.compileCheck(resource, action, codec.parse(policy.effectiveCheckScope()));
        }
        if (request.enabled() != null) {
            if (request.enabled() && request.scope() == null) {
                // Switching a quarantined policy back on without touching its
                // condition. Compiled here so the answer is a 422 naming the
                // node, rather than an apparent success followed by the loader
                // quietly disabling it again a moment later.
                compiler.compile(resource, action, codec.parse(policy.getScope()));
                compiler.compileCheck(resource, action, codec.parse(policy.effectiveCheckScope()));
            }
            policy.setEnabled(request.enabled());
            if (request.enabled()) {
                // It compiled, so whatever the loader objected to is gone.
                policy.setInvalidReason(null);
            }
        }
        policy.setUpdatedBy(currentUser());

        RowLevelPolicy saved = repository.save(policy);
        record(saved, operationFor(request, wasEnabled), before, snapshot(saved));
        announce();
        return saved;
    }

    @Transactional
    public RowLevelPolicy delete(Long id) {
        RowLevelPolicy policy = get(id);
        String before = snapshot(policy);

        // Recorded before the row goes, and the audit table has no foreign key
        // back to it, so the entry outlives the policy.
        record(policy, RowLevelPolicyAudit.Operation.DELETE, before, null);
        repository.delete(policy);
        announce();

        // Returned so the controller can echo the deleted row.
        return policy;
    }

    public RowLevelPolicyResponse toResponse(RowLevelPolicy policy) {
        return RowLevelPolicyResponse.from(
                policy,
                codec.read(policy.getScope()),
                // Null stays null: the screen has to be able to tell "no
                // separate check" from "a check that happens to match".
                policy.getCheckScope() == null ? null : codec.read(policy.getCheckScope()));
    }

    private void announce() {
        events.publishEvent(new RowLevelPoliciesChanged.Changed());
    }

    private RowLevelRegistry.Managed resource(String name) {
        return registry.byResource(name).orElseThrow(() -> new PolicyValidationException(
                "resource",
                "\"%s\" is not under row-level management. Managed: %s".formatted(
                        name, registry.all().stream().map(RowLevelRegistry.Managed::resource).toList())));
    }

    private static PolicyKind kind(String value) {
        try {
            return PolicyKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PolicyValidationException("kind", "must be SCOPE or FILTER");
        }
    }

    private static Action action(String value) {
        try {
            return Action.from(value);
        } catch (IllegalArgumentException e) {
            throw new PolicyValidationException("action", "must be read, write or delete");
        }
    }

    /**
     * The role a policy belongs to, and the check that the two kinds do not blur.
     *
     * <p>A {@code FILTER} with a role would be escapable by not holding that
     * role, which is the one thing a prohibition must not be. A {@code SCOPE}
     * without one would widen nobody. The database says the same thing in
     * {@code ck_rlp_kind}; saying it here only buys a message instead of a
     * constraint name.
     */
    private Role role(PolicyKind kind, RoleRef ref) {
        Long id = ref == null ? null : ref.id();

        if (kind == PolicyKind.FILTER) {
            if (id != null) {
                throw new PolicyValidationException("role",
                        "a FILTER applies to everyone, so it cannot name a role");
            }
            return null;
        }
        if (id == null) {
            throw new PolicyValidationException("role", "a SCOPE has to name the role it widens");
        }
        return roleService.get(id);
    }

    private static RowLevelPolicyAudit.Operation operationFor(
            RowLevelPolicyPatchRequest request, boolean wasEnabled) {

        // A change that only flips the switch is recorded as what it is, so that
        // reading the trail does not mean diffing two JSON blobs to find out
        // that somebody turned a rule off.
        if (request.enabled() != null && request.enabled() != wasEnabled
                && request.scope() == null && request.role() == null && request.action() == null
                && request.name() == null && request.policyGroup() == null && request.description() == null) {
            return request.enabled()
                    ? RowLevelPolicyAudit.Operation.ENABLE
                    : RowLevelPolicyAudit.Operation.DISABLE;
        }
        return RowLevelPolicyAudit.Operation.UPDATE;
    }

    private void record(
            RowLevelPolicy policy,
            RowLevelPolicyAudit.Operation operation,
            String before,
            String after) {

        auditRepository.save(new RowLevelPolicyAudit(
                policy.getId(),
                operation,
                before,
                after,
                currentUser(),
                // Captured rather than joined, so the entry stays readable once
                // the account is gone.
                CurrentUser.username().orElse("system")));
    }

    /** The policy as JSON, for the audit trail. */
    private String snapshot(RowLevelPolicy policy) {
        JsonNode node = mapper.valueToTree(toResponse(policy));
        return mapper.writeValueAsString(node);
    }

    /** Who is making the change, off the access token. Null on an unauthenticated call. */
    private User currentUser() {
        return CurrentUser.id().map(userService::get).orElse(null);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
