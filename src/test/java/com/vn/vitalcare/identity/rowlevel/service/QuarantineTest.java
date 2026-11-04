package com.vn.vitalcare.identity.rowlevel.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vn.vitalcare.identity.role.entity.Role;
import com.vn.vitalcare.identity.rowlevel.entity.PolicyKind;
import com.vn.vitalcare.identity.rowlevel.entity.RowLevelPolicy;
import com.vn.vitalcare.identity.rowlevel.repository.RowLevelPolicyRepository;
import com.vn.vitalcare.share.security.rowlevel.Action;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T14 and T15 — what happens to a policy that no longer compiles. Ported from
 * the framework, which ran it over {@code tasks}; here it uses {@code devices}.
 *
 * <p>Not {@code @Transactional}, unlike the other database-backed tests here.
 * The loader quarantines in a {@code REQUIRES_NEW} transaction, because it
 * normally runs from an {@code AFTER_COMMIT} listener where the surrounding
 * transaction is already over — and a test transaction that rolled everything
 * back would never see the write it is checking for. So the rows are cleaned up
 * by hand instead.
 */
@SpringBootTest
class QuarantineTest {

    private static final String RESOURCE = "devices";

    @Autowired
    private RowLevelPolicyRepository repository;

    @Autowired
    private RowLevelPolicyCache cache;

    @Autowired
    private EntityManager entityManager;

    private Long policyId;

    @AfterEach
    void tidyUp() {
        if (policyId != null) {
            repository.deleteById(policyId);
            policyId = null;
        }
        cache.reload();
    }

    @Test
    @DisplayName("T15: a broken SCOPE is disabled with a reason, and does not stop the node")
    void aBrokenScopeIsQuarantined() {
        policyId = save(PolicyKind.SCOPE, Action.READ, """
                {"field": "nosuchfield", "op": "eq", "value": {"lit": 1}}""");

        // Reaching here at all is half the test: loading is what the node does
        // at startup, and it must not throw.
        cache.reload();

        RowLevelPolicy reloaded = repository.findById(policyId).orElseThrow();
        assertFalse(reloaded.isEnabled(), "a policy that will not compile must be switched off");
        assertNotNull(reloaded.getInvalidReason(), "and the reason has to be written down");
        assertTrue(reloaded.getInvalidReason().contains("nosuchfield"),
                "the reason must name what is wrong: " + reloaded.getInvalidReason());

        assertTrue(isQuarantined(policyId), "and it has to be reported as quarantined");
        // Under DefaultScope.NONE, which devices use, this is fail-closed: the
        // scope stops widening anybody.
        assertFalse(cache.isBlocked(RESOURCE, Action.READ),
                "a SCOPE must not close the pair -- only a FILTER does that");
    }

    @Test
    @DisplayName("T14: a broken FILTER additionally closes its whole (resource, action)")
    void aBrokenFilterBlocksEverything() {
        policyId = save(PolicyKind.FILTER, Action.READ, """
                {"field": "nosuchfield", "op": "eq", "value": {"lit": 1}}""");

        cache.reload();

        // A prohibition that quietly stopped applying is a data leak. Losing the
        // screen loudly is the worse day; leaking is the worse year.
        assertTrue(cache.isBlocked(RESOURCE, Action.READ),
                "a quarantined FILTER must close the pair it was protecting");
        assertFalse(cache.isBlocked(RESOURCE, Action.WRITE),
                "and only that pair -- it says nothing about the others");
    }

    @Test
    @DisplayName("A quarantine survives the next load, so the alert does not vanish on restart")
    void quarantineIsStillReportedAfterAReload() {
        policyId = save(PolicyKind.FILTER, Action.READ, """
                {"field": "nosuchfield", "op": "eq", "value": {"lit": 1}}""");

        cache.reload();
        assertTrue(isQuarantined(policyId));

        // The second load sees a row that is already disabled. Skipping it
        // silently would clear the health check while the policy is still not
        // applying -- an alert that disappears at the next restart is an alert
        // nobody ever acts on.
        cache.reload();
        assertTrue(isQuarantined(policyId), "the quarantine must still be reported");
        assertTrue(cache.isBlocked(RESOURCE, Action.READ), "and the block must still be in force");
    }

    @Test
    @DisplayName("A policy for a resource this node does not manage is skipped, never disabled")
    void anUnknownResourceIsNotQuarantined() {
        // What a rolling deploy looks like from an old node: the new version has
        // brought a resource under management and written a policy for it, and
        // this node has never heard of it.
        policyId = save(PolicyKind.SCOPE, Action.READ, """
                {"field": "id", "op": "eq", "value": {"lit": 1}}""", "not_a_managed_resource");

        cache.reload();

        RowLevelPolicy reloaded = repository.findById(policyId).orElseThrow();
        // The row must be left exactly as it was found. Disabling it here would
        // disable it for every node -- including the ones that understand it --
        // and it would stay disabled long after the deploy finished.
        assertTrue(reloaded.isEnabled(), "an unknown resource must not disable the policy");
        assertNull(reloaded.getInvalidReason(), "and must not be recorded as broken");
        assertFalse(isQuarantined(policyId), "nor reported as quarantined");
    }

    /**
     * Whether the loader is reporting <em>this</em> policy.
     *
     * <p>Deliberately not a count of everything quarantined: these tests run
     * against a shared development database, and asserting a total makes them
     * fail for whatever somebody else left behind rather than for what they are
     * about.
     */
    private boolean isQuarantined(Long id) {
        return cache.quarantined().stream()
                .anyMatch(policy -> policy.policyId().equals(id));
    }

    private Long save(PolicyKind kind, Action action, String scope) {
        return save(kind, action, scope, RESOURCE);
    }

    private Long save(PolicyKind kind, Action action, String scope, String resource) {
        Role admin = entityManager
                .createQuery("select r from Role r where r.code = 'ADMIN'", Role.class)
                .getSingleResult();

        RowLevelPolicy policy = new RowLevelPolicy(
                kind,
                kind == PolicyKind.SCOPE ? admin : null,
                resource,
                action.code(),
                "Broken on purpose",
                scope);
        return repository.save(policy).getId();
    }
}
