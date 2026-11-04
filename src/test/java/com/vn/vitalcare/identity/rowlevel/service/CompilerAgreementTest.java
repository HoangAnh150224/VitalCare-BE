package com.vn.vitalcare.identity.rowlevel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vn.vitalcare.entity.Clinic;
import com.vn.vitalcare.entity.DeviceStatus;
import com.vn.vitalcare.entity.MedicalDevice;
import com.vn.vitalcare.share.security.rowlevel.Action;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import com.vn.vitalcare.share.security.rowlevel.Tri;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * T4 — the test the whole design exists to make possible, and the one that must
 * never be deleted. Ported from the framework, which ran it over {@code tasks};
 * here it runs over {@code devices}, the managed resource whose clinic is a
 * nullable UUID column compared against the {@code user.clinicId} context key.
 *
 * <p>One declaration, two compilers. {@code PredicateCompiler} turns a plan into
 * SQL and {@code EntityEvaluator} answers the same plan in memory, and they are
 * used for different things — the first decides which rows a list contains, the
 * second decides whether a save is allowed and which buttons a row gets. If they
 * ever disagree, a row is visible in a list and refused on save, or worse,
 * refused in a list and accepted on save.
 *
 * <p>So: for each tree, the set of device ids the database returns must be
 * exactly the set the in-memory evaluator accepts. The fixture covers the cases
 * where the two are most likely to part company — a {@code not} over a nullable
 * column, a context key that resolves to null, and an empty set.
 *
 * <p>Runs against the development database inside a transaction that is rolled
 * back, like {@code BaseEntityTest}.
 */
@SpringBootTest
@Transactional
class CompilerAgreementTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RowLevelRegistry registry;

    @Autowired
    private PolicyCompiler compiler;

    @Autowired
    private ScopeTreeCodec codec;

    @Autowired
    private PredicateCompiler predicates;

    @Autowired
    private EntityEvaluator evaluator;

    private RowLevelRegistry.Managed devices;
    private List<MedicalDevice> fixture;
    private Clinic here;
    private Clinic elsewhere;

    /**
     * Every combination of "the clinic is null", "the clinic is the caller's"
     * and "the clinic is another", against a nullable text column.
     *
     * <p>Written out rather than reused from the seed data, because the seed is
     * demo content that somebody will reasonably change, and the cases that
     * matter here are precisely the awkward ones nobody puts in demo content.
     */
    @BeforeEach
    void setUp() {
        devices = registry.byResource("devices").orElseThrow();

        List<Clinic> clinics = entityManager
                .createQuery("select c from Clinic c order by c.clinicId", Clinic.class)
                .setMaxResults(2).getResultList();
        here = clinics.get(0);
        elsewhere = clinics.get(1);

        fixture = new ArrayList<>();
        fixture.add(device(here, DeviceStatus.AVAILABLE, "WRIST_MONITOR"));
        fixture.add(device(elsewhere, DeviceStatus.MAINTENANCE, null));
        fixture.add(device(null, DeviceStatus.AVAILABLE, "WRIST_MONITOR"));
        fixture.add(device(null, DeviceStatus.RETIRED, null));
        fixture.add(device(here, DeviceStatus.ASSIGNED, null));
        fixture.add(device(elsewhere, DeviceStatus.AVAILABLE, "WRISTXMONITOR"));

        entityManager.flush();
    }

    static Stream<String> trees() {
        return Stream.of(
                // The plain cases, to prove the harness itself works.
                """
                { "field": "clinicId", "op": "eq", "value": { "ctx": "user.clinicId" } }""",
                """
                { "field": "status", "op": "in", "value": { "lit": ["AVAILABLE", "MAINTENANCE"] } }""",
                // like means "contains", case-insensitively, on both sides.
                """
                { "field": "deviceType", "op": "like", "value": { "lit": "wrist" } }""",
                // A literal's own _ and % are characters, not SQL wildcards:
                // "t_monitor" must not match "WRIST_MONITOR" through the _ ...
                """
                { "field": "deviceType", "op": "like", "value": { "lit": "wrist_mon" } }""",
                """
                { "field": "deviceType", "op": "like", "value": { "lit": "wristxmon" } }""",
                // ... nor "%" match everything.
                """
                { "field": "deviceType", "op": "like", "value": { "lit": "%" } }""",
                """
                { "field": "deviceType", "op": "notLike", "value": { "lit": "_" } }""",

                // A not over a nullable column. A device with no clinic is
                // UNKNOWN to both, and UNKNOWN refuses the row.
                """
                { "not": { "field": "clinicId", "op": "eq", "value": { "ctx": "user.clinicId" } } }""",
                """
                { "not": { "field": "deviceType", "op": "eq", "value": { "lit": "WRIST_MONITOR" } } }""",

                // De Morgan across a whole subtree.
                """
                { "not": { "all": [
                    { "field": "clinicId", "op": "eq", "value": { "ctx": "user.clinicId" } },
                    { "field": "status",   "op": "eq", "value": { "lit": "AVAILABLE" } }
                ]}}""",
                """
                { "not": { "any": [
                    { "field": "clinicId", "op": "eq", "value": { "ctx": "user.clinicId" } },
                    { "field": "deviceType", "op": "isNotNull" }
                ]}}""",

                // The isNull branch somebody has to write out by hand once they
                // understand why the not above does not match a null.
                """
                { "any": [
                    { "not": { "field": "clinicId", "op": "eq", "value": { "ctx": "user.clinicId" } } },
                    { "field": "clinicId", "op": "isNull" }
                ]}""",
                """
                { "field": "clinicId", "op": "isNull" }""",
                """
                { "field": "deviceType", "op": "isNotNull" }""",

                // UUID literals, the type this port added.
                """
                { "field": "clinicId", "op": "in",
                  "value": { "lit": ["5b1f8c2e-6a3d-4f7e-9c41-2d8e0a7b3c19", "c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48"] } }""",
                """
                { "field": "clinicId", "op": "ne", "value": { "lit": "c2a7e4d1-3b8f-4c62-9a15-7e0d3f9b2a48" } }""",

                // Empty sets, where the answer is a definition this design made
                // rather than something SQL could express: `x IN ()` cannot be
                // written at all.
                """
                { "field": "status", "op": "in", "value": { "lit": [] } }""",
                """
                { "field": "clinicId", "op": "notIn", "value": { "lit": [] } }""",

                // The full scope and the empty one.
                "{ \"all\": [] }",
                "{ \"any\": [] }");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trees")
    @DisplayName("T4: SQL and the in-memory evaluator select exactly the same rows")
    void bothCompilersAgree(String json) {
        ScopePlan plan = compiler.compile(devices, Action.READ, codec.parse(json));

        // A principal whose clinic is one the fixture uses, so the conditions
        // actually split the rows rather than matching all or none.
        RowLevelPrincipal principal = principal(here.getClinicId());

        assertEquals(
                inMemory(plan, principal),
                fromDatabase(plan, principal),
                "the two compilers disagree about " + json);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trees")
    @DisplayName("T4: they still agree when a context key resolves to null")
    void bothCompilersAgreeWithNullContext(String json) {
        ScopePlan plan = compiler.compile(devices, Action.READ, codec.parse(json));

        // Somebody with no staff record. A comparison against nothing is
        // UNKNOWN, and UNKNOWN refuses the row -- on both sides.
        RowLevelPrincipal principal = principal(null);

        assertEquals(
                inMemory(plan, principal),
                fromDatabase(plan, principal),
                "the two compilers disagree about " + json + " when the context is null");
    }

    @Test
    @DisplayName("Three-valued logic in practice: not(clinic = mine) does not match a device with no clinic")
    void notDoesNotMatchNull() {
        ScopePlan plan = compiler.compile(devices, Action.READ, codec.parse("""
                { "not": { "field": "clinicId", "op": "eq", "value": { "ctx": "user.clinicId" } } }"""));
        RowLevelPrincipal principal = principal(here.getClinicId());

        MedicalDevice unplaced = fixture.get(2);
        assertEquals(Tri.UNKNOWN, evaluator.evaluate(plan, unplaced, principal));
        assertFalse(fromDatabase(plan, principal).contains(unplaced.getId()),
                "a device with no clinic must not be selected by a negated condition on the clinic");

        // And the branch somebody writes to get the other behaviour does match.
        ScopePlan spelledOut = compiler.compile(devices, Action.READ, codec.parse("""
                { "any": [
                    { "not": { "field": "clinicId", "op": "eq", "value": { "ctx": "user.clinicId" } } },
                    { "field": "clinicId", "op": "isNull" }
                ]}"""));
        assertTrue(fromDatabase(spelledOut, principal).contains(unplaced.getId()));
    }

    /** The ids the database returns for this plan, restricted to the fixture. */
    private Set<Long> fromDatabase(ScopePlan plan, RowLevelPrincipal principal) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<MedicalDevice> root = query.from(MedicalDevice.class);

        query.select(root.get("id")).where(cb.and(
                root.get("id").in(fixture.stream().map(MedicalDevice::getId).toList()),
                predicates.toPredicate(plan, root, query, cb, principal)));

        return new LinkedHashSet<>(entityManager.createQuery(query).getResultList());
    }

    /** The ids the in-memory evaluator accepts, over the same rows. */
    private Set<Long> inMemory(ScopePlan plan, RowLevelPrincipal principal) {
        Set<Long> accepted = new LinkedHashSet<>();
        for (MedicalDevice device : fixture) {
            if (evaluator.evaluate(plan, device, principal).isTrue()) {
                accepted.add(device.getId());
            }
        }
        return accepted;
    }

    private MedicalDevice device(Clinic clinic, DeviceStatus status, String deviceType) {
        MedicalDevice device = new MedicalDevice();
        // Unique per run; the transaction is rolled back, so nothing is left.
        device.setDeviceCode("T4-" + UUID.randomUUID());
        device.setClinic(clinic);
        device.setStatus(status);
        device.setDeviceType(deviceType);
        entityManager.persist(device);
        return device;
    }

    private static RowLevelPrincipal principal(UUID clinicId) {
        return new RowLevelPrincipal(1L, null, null, clinicId, Set.of("MANAGER"), Set.of(1L));
    }
}
