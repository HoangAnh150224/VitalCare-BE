package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.identity.rowlevel.entity.PolicyKind;
import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicy;
import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicyAudit;
import com.vn.vitalcare.identity.rowlevel.repository.RowLevelPolicyAuditRepository;
import com.vn.vitalcare.identity.rowlevel.repository.RowLevelPolicyRepository;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.RowLevelGeneration;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPoliciesChanged;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every runtime policy, compiled, in memory.
 *
 * <p>The same shape as {@code AuthorityCache} — the same events, the same bus,
 * the same sixty-second backstop — because it is solving the same problem and a
 * second pattern for it would be a second thing to reason about. No Redis:
 * {@code PostgresEventBus} already does this job over infrastructure the
 * application cannot run without anyway.
 *
 * <h2>Whole-table, pointer-swap</h2>
 *
 * <p>The policy set is small (tens of rows, maybe hundreds) and almost every
 * query needs some of it, so there is nothing to gain from loading by key.
 * Loading all of it, compiling all of it, and publishing it as one immutable
 * {@link Snapshot} behind a {@code volatile} reference means invalidation is
 * "load again and swap the pointer" — with no partial eviction to be subtly
 * wrong, and no window in which half the rules are the new ones.
 *
 * <h2>Quarantine, and why it leans two different ways</h2>
 *
 * <p>A policy that no longer compiles — the schema moved under it, somebody ran
 * a migration by hand, a backup was restored — is disabled rather than allowed
 * to stop the node from starting. Refusing to boot over one row of data turns a
 * configuration mistake into an outage, at whatever arbitrary later restart
 * happens to surface it.
 *
 * <p>But disabling is not neutral, and the direction depends on the kind. A
 * broken {@code SCOPE} that stops applying makes the scope <em>narrower</em>:
 * quarantine alone is fail-closed and is enough. A broken {@code FILTER} that
 * stops applying makes a prohibition <em>disappear</em>, which is a data leak —
 * so quarantining one additionally blocks its entire {@code (resource, action)}
 * until somebody fixes it. Blocking everything is a worse day than losing one
 * rule; leaking is a worse year.
 */
@Component
public class RowLevelPolicyCache implements RowLevelGeneration {

    private static final Logger log = LoggerFactory.getLogger(RowLevelPolicyCache.class);

    /**
     * The last line of defence, not the mechanism. Nothing should ever need it;
     * it is here so that a path somebody forgets to publish an event from
     * degrades to "wrong for a minute" instead of "wrong until the next
     * restart". Same number and same reasoning as {@code AuthorityCache}.
     */
    static final long TTL_SECONDS = 60;

    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);

    private final RowLevelPolicyRepository repository;
    private final RowLevelPolicyAuditRepository auditRepository;
    private final RowLevelRegistry registry;
    private final PolicyCompiler compiler;
    private final ScopeTreeCodec codec;
    private final TransactionTemplate quarantineTransaction;

    private volatile Snapshot snapshot;

    public RowLevelPolicyCache(RowLevelPolicyRepository repository,
                               RowLevelPolicyAuditRepository auditRepository,
                               RowLevelRegistry registry,
                               PolicyCompiler compiler,
                               ScopeTreeCodec codec,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.registry = registry;
        this.compiler = compiler;
        this.codec = codec;

        // Its own transaction, because quarantine happens while loading, and
        // loading happens from an AFTER_COMMIT listener where the surrounding
        // transaction is over. Writing the disable and its audit entry must
        // either both happen or neither.
        this.quarantineTransaction = new TransactionTemplate(transactionManager);
        this.quarantineTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The compiled policy set as of now. Loads on first use, then on every change. */
    public Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null || current.isExpired()) {
            reload();
            current = snapshot;
        }
        return current;
    }

    /** Every enabled policy for one resource and action, in no particular order. */
    public List<CompiledPolicy> policiesFor(String resource, Action action) {
        return snapshot().byResourceAction().getOrDefault(new Key(resource, action), List.of());
    }

    /** True when a broken {@code FILTER} has closed this pair entirely. */
    public boolean isBlocked(String resource, Action action) {
        return snapshot().blocked().contains(new Key(resource, action));
    }

    @Override
    public String generation() {
        return snapshot().generation();
    }

    /** Policies the loader had to disable, for the health indicator to report. */
    public List<Quarantined> quarantined() {
        return snapshot().quarantined();
    }

    /**
     * Loads before the first request rather than on it, so that a schema that
     * has moved under a policy is discovered by the deployment rather than by a
     * user.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadAtStartup() {
        try {
            reload();
        } catch (RuntimeException e) {
            // Not fatal: the first read will try again, and failing to start
            // over a transient database hiccup helps nobody.
            log.error("Could not load row-level policies at startup", e);
        }
    }

    /**
     * Applies a change made on this node.
     *
     * <p>{@code AFTER_COMMIT} because reloading before the commit lets a
     * concurrent request read back the very rows that are about to change.
     * {@code fallbackExecution} because without it an event published outside a
     * transaction is dropped in silence — the worst possible failure mode for an
     * invalidation. Ordered first so this node is never the last to know about
     * its own change.
     */
    @Order(0)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLocalChange(RowLevelPoliciesChanged change) {
        reload();
    }

    /**
     * Applies a change that reached this node from another one.
     *
     * <p>A plain listener: the transaction it belongs to committed on a
     * different machine, so there is nothing here to wait for.
     */
    @Order(0)
    @EventListener
    public void onRemoteChange(RowLevelPolicyChannel.Received received) {
        reload();
    }

    /** Rebuilds the snapshot from the table. Safe to call concurrently; the last writer wins. */
    public synchronized void reload() {
        List<RowLevelPolicy> rows = repository.findAll();

        Map<Key, List<CompiledPolicy>> compiled = new LinkedHashMap<>();
        Set<Key> blocked = new LinkedHashSet<>();
        List<Quarantined> quarantined = new ArrayList<>();
        Set<String> unmanaged = new TreeSet<>();
        Set<String> stamp = new TreeSet<>();

        for (RowLevelPolicy policy : rows) {
            if (!policy.isEnabled()) {
                // A policy an administrator switched off carries no reason and
                // is simply not in force. One the loader disabled carries one,
                // and has to keep being reported: it is still a security
                // control that is not applying, and letting the alert
                // disappear at the next restart is how a quarantine turns into
                // a permanent silence.
                if (policy.getInvalidReason() != null) {
                    quarantined.add(new Quarantined(
                            policy.getId(), policy.getKind(), policy.getResource(),
                            policy.getAction(), policy.getInvalidReason()));
                    if (policy.getKind() == PolicyKind.FILTER) {
                        blockedKey(policy).ifPresent(blocked::add);
                    }
                }
                continue;
            }

            // A resource this node has never heard of is NOT a broken policy, and
            // the difference is the one that makes a rolling deploy safe.
            Optional<RowLevelRegistry.Managed> managed = registry.byResource(policy.getResource());
            if (managed.isEmpty()) {
                unmanaged.add(policy.getResource());
                continue;
            }

            Key key;
            ScopePlan plan;
            ScopePlan checkPlan;
            try {
                key = keyOf(policy);
                Action action = Action.from(policy.getAction());
                plan = compiler.compile(managed.get(), action, codec.parse(policy.getScope()));
                // Null check_scope means "the same as USING", which is both
                // Postgres's default and the safe one. It still goes through
                // compileCheck, so a field that only exists after the flush is
                // caught whichever clause it ended up governing.
                checkPlan = compiler.compileCheck(
                        managed.get(), action, codec.parse(policy.effectiveCheckScope()));
            } catch (RuntimeException e) {
                quarantine(policy, e.getMessage());
                quarantined.add(new Quarantined(
                        policy.getId(), policy.getKind(), policy.getResource(),
                        policy.getAction(), e.getMessage()));

                if (policy.getKind() == PolicyKind.FILTER) {
                    // A prohibition that stopped applying is a leak. Closing the
                    // whole pair is the only answer that is wrong in the safe
                    // direction.
                    blockedKey(policy).ifPresent(blocked::add);
                }
                continue;
            }

            compiled.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new CompiledPolicy(
                    policy.getId(),
                    policy.getKind(),
                    policy.getRole() == null ? null : policy.getRole().getId(),
                    policy.getName(),
                    plan,
                    checkPlan));

            // The stamp is over what is actually in force, so a policy that was
            // quarantined this boot and fixed the next one moves it -- which is
            // exactly when clients should re-read.
            stamp.add("%d:%s:%s:%s:%s:%s".formatted(
                    policy.getId(), policy.getKind(), policy.getResource(),
                    policy.getAction(), policy.getScope(), policy.effectiveCheckScope()));
        }

        Map<Key, List<CompiledPolicy>> immutable = new LinkedHashMap<>();
        compiled.forEach((key, value) -> immutable.put(key, List.copyOf(value)));

        // String.hashCode is specified by the language, so every node computes
        // the same number for the same policy set -- which is the property this
        // needs and Object.hashCode does not have.
        String generation = Integer.toHexString(String.join("|", stamp).hashCode());

        snapshot = new Snapshot(
                Map.copyOf(immutable),
                Set.copyOf(blocked),
                List.copyOf(quarantined),
                generation,
                System.nanoTime());

        if (!quarantined.isEmpty()) {
            log.error("{} row-level policies are quarantined and not in force: {}",
                    quarantined.size(), quarantined);
        }
        if (!unmanaged.isEmpty()) {
            // Warn, do not fail health: during a rolling deploy every old node
            // would otherwise report DOWN for the whole window, which is noise
            // rather than news.
            log.warn("Ignoring policies for resources this node does not manage: {}", unmanaged);
        }
    }

    private Key keyOf(RowLevelPolicy policy) {
        return new Key(policy.getResource(), Action.from(policy.getAction()));
    }

    private Optional<Key> blockedKey(RowLevelPolicy policy) {
        try {
            return Optional.of(keyOf(policy));
        } catch (IllegalArgumentException e) {
            // The action itself is unreadable, so there is no pair to block.
            // The policy is disabled either way, and the health indicator says
            // so; inventing a pair to close would close the wrong one.
            return Optional.empty();
        }
    }

    /**
     * Disables a policy that no longer compiles, and records why.
     *
     * <p>Written down rather than only logged: the reason has to be visible in
     * the admin screen next to the policy, because the person who can fix it is
     * looking at that screen, not at the node's log.
     */
    private void quarantine(RowLevelPolicy policy, String reason) {
        String trimmed = reason == null ? "Does not compile" : reason.substring(0, Math.min(reason.length(), 500));
        try {
            quarantineTransaction.executeWithoutResult(status -> {
                RowLevelPolicy row = repository.findById(policy.getId()).orElse(null);
                if (row == null || !row.isEnabled()) {
                    return;
                }
                row.setEnabled(false);
                row.setInvalidReason(trimmed);
                repository.save(row);
                auditRepository.save(new RowLevelPolicyAudit(
                        row.getId(), RowLevelPolicyAudit.Operation.QUARANTINE,
                        null, null, null, "system"));
            });
        } catch (RuntimeException e) {
            // The policy is out of the snapshot regardless, which is the part
            // that matters for correctness. Failing the whole load because the
            // note could not be written would be trading a working node for a
            // tidier record.
            log.error("Could not record the quarantine of policy {}", policy.getId(), e);
        }
    }

    /** A {@code (resource, action)} pair — how policies are looked up. */
    public record Key(String resource, Action action) {
    }

    /**
     * One policy, compiled and ready to use.
     *
     * @param plan      the {@code USING} clause: which rows may be reached
     * @param checkPlan the {@code WITH CHECK} clause: what a row may look like
     *                  after a write. Equal to {@code plan} unless the policy
     *                  declared its own, which is what lets "you may edit
     *                  drafts" not also mean "and never publish one"
     */
    public record CompiledPolicy(
            Long policyId,
            PolicyKind kind,
            Long roleId,
            String name,
            ScopePlan plan,
            ScopePlan checkPlan) {
    }

    /** A policy the loader had to disable, and why. */
    public record Quarantined(
            Long policyId,
            PolicyKind kind,
            String resource,
            String action,
            String reason) {
    }

    /**
     * The whole policy set at one instant.
     *
     * <p>Immutable and replaced wholesale, never edited in place — which is what
     * makes a reader either see all of the old rules or all of the new ones.
     */
    public record Snapshot(
            Map<Key, List<CompiledPolicy>> byResourceAction,
            Set<Key> blocked,
            List<Quarantined> quarantined,
            String generation,
            long loadedAt) {

        boolean isExpired() {
            return System.nanoTime() - loadedAt > TTL.toNanos();
        }
    }
}
