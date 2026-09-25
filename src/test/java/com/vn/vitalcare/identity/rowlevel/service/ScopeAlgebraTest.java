package com.vn.vitalcare.identity.rowlevel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import com.vn.vitalcare.share.security.rowlevel.Tri;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T1 and T5 — how rules combine, and the property that makes the combination
 * safe to hand to an administrator.
 *
 * <p>Built out of hand-made {@link EffectiveScope}s rather than through the
 * database, because these are claims about <em>combination</em>, and the
 * combining is the part with no SQL in it. {@code Ticket} below is a bare
 * fixture standing in for a real managed entity — nothing here reaches the
 * registry or a repository, so it needs no persistence and no annotation.
 */
class ScopeAlgebraTest {

    private enum FixtureStatus {
        TODO, IN_PROGRESS, DONE, BLOCKED, CANCELLED
    }

    private record Ticket(String title, FixtureStatus status) {
    }

    /** Only {@code evaluator} is reached on these paths; the rest belong to the SQL side. */
    private final RowLevelSecurityImpl security = new RowLevelSecurityImpl(
            null, null, null, null, new EntityEvaluator(), null);

    @Test
    @DisplayName("T1: scopes union, so a second role can only ever widen")
    void scopesUnion() {
        Ticket todo = ticket(FixtureStatus.TODO);

        List<EffectiveScope.Contribution> both = List.of(
                matching("CLOSER", false),   // everything except TODO
                matching("OPENER", true));   // only TODO
        EffectiveScope effective = scope(DefaultScope.NONE, both, List.of());

        assertEquals(Tri.FALSE, security.evaluate(effective, todo, holding("CLOSER")));
        assertEquals(Tri.TRUE, security.evaluate(effective, todo, holding("CLOSER", "OPENER")));
    }

    @Test
    @DisplayName("T1 and T6: filters intersect, and there is no role to not-hold in order to escape one")
    void filtersIntersect() {
        Ticket todo = ticket(FixtureStatus.TODO);

        EffectiveScope wideOpen = scope(DefaultScope.FULL, List.of(), List.of());
        assertEquals(Tri.TRUE, security.evaluate(wideOpen, todo, holding()));

        EffectiveScope forbidden = scope(DefaultScope.FULL, List.of(), List.of(refusing()));
        assertEquals(Tri.FALSE, security.evaluate(forbidden, todo, holding()));
        assertEquals(Tri.FALSE, security.evaluate(forbidden, todo, holding("OPENER")));
    }

    @Test
    @DisplayName("T1: with no applicable scope, the resource's declared default decides")
    void defaultScopeApplies() {
        Ticket todo = ticket(FixtureStatus.TODO);

        assertEquals(Tri.TRUE,
                security.evaluate(scope(DefaultScope.FULL, List.of(), List.of()), todo, holding()));
        assertEquals(Tri.FALSE,
                security.evaluate(scope(DefaultScope.NONE, List.of(), List.of()), todo, holding()));
    }

    @Test
    @DisplayName("T1: a scope belonging to a role the caller does not hold contributes nothing")
    void scopesOfOtherRolesAreIgnored() {
        EffectiveScope somebodyElses =
                scope(DefaultScope.NONE, List.of(matching("SOMEBODY_ELSE", true)), List.of());

        assertEquals(Tri.FALSE, security.evaluate(somebodyElses, ticket(FixtureStatus.TODO), holding("OPENER")));
    }

    @Test
    @DisplayName("T14: a quarantined FILTER closes the pair rather than letting the prohibition vanish")
    void blockedRefusesEverything() {
        EffectiveScope blocked = new EffectiveScope("widgets", DefaultScope.FULL, List.of(), List.of(), true);
        assertEquals(Tri.FALSE, security.evaluate(blocked, ticket(FixtureStatus.TODO), holding("OPENER")));
    }

    /**
     * T5 — monotonicity, generatively, under {@link DefaultScope#NONE}.
     *
     * <p>The property an administrator relies on without ever being told about
     * it: granting an extra role must never take a row away. It holds because
     * scopes are unioned, and it would break the moment somebody "fixed" that to
     * an intersection — a change that looks entirely reasonable in isolation,
     * which is why this is generated rather than a handful of examples.
     */
    @Test
    @DisplayName("T5: under NONE, adding a role never reduces what is visible")
    void addingARoleNeverRemovesRows() {
        Random random = new Random(20260903L);
        List<Ticket> rows = List.of(
                ticket(FixtureStatus.TODO), ticket(FixtureStatus.IN_PROGRESS),
                ticket(FixtureStatus.DONE), ticket(FixtureStatus.BLOCKED), ticket(FixtureStatus.CANCELLED));
        List<String> allRoles = List.of("R1", "R2", "R3", "R4", "R5", "R6");

        for (int trial = 0; trial < 200; trial++) {
            List<EffectiveScope.Contribution> scopes = new ArrayList<>();
            for (String role : allRoles) {
                scopes.add(matching(role, random.nextBoolean()));
            }
            EffectiveScope effective = scope(DefaultScope.NONE, scopes, List.of());

            Set<String> held = new LinkedHashSet<>();
            Set<String> before = visible(effective, rows, held);

            for (String role : allRoles) {
                held.add(role);
                Set<String> after = visible(effective, rows, held);

                assertTrue(after.containsAll(before),
                        "adding %s removed rows: %s -> %s".formatted(role, before, after));
                before = after;
            }
        }
    }

    /**
     * The one place monotonicity does not hold, stated rather than hidden.
     *
     * <p>Under {@link DefaultScope#FULL}, an account holding no scoped role at
     * all falls through to "everything". Give it its first scoped role and it is
     * narrowed — so that one step does remove rows.
     *
     * <p>That is the price of {@code FULL}, and paying it is the whole reason
     * {@code FULL} exists: it is what lets a running resource be brought under
     * management without anybody losing a row on the day it ships.
     */
    @Test
    @DisplayName("T5: under FULL the first scoped role narrows — and every role after it still only widens")
    void fullNarrowsOnlyOnTheFirstScopedRole() {
        Ticket done = ticket(FixtureStatus.DONE);
        EffectiveScope effective = scope(
                DefaultScope.FULL,
                List.of(matching("OPENER", true), matching("CLOSER", false)),
                List.of());

        // No scoped role: not narrowed at all.
        assertEquals(Tri.TRUE, security.evaluate(effective, done, holding()));
        assertEquals(Tri.TRUE, security.evaluate(effective, done, holding("UNSCOPED")));

        // The first scoped role narrows. This is the documented cost of FULL.
        assertFalse(security.evaluate(effective, done, holding("OPENER")).isTrue());

        // And from there on, more roles only ever widen again.
        assertEquals(Tri.TRUE, security.evaluate(effective, done, holding("OPENER", "CLOSER")));
    }

    private Set<String> visible(EffectiveScope effective, List<Ticket> rows, Set<String> roleCodes) {
        RowLevelPrincipal principal = holding(roleCodes.toArray(String[]::new));

        Set<String> titles = new LinkedHashSet<>();
        for (Ticket row : rows) {
            if (security.evaluate(effective, row, principal).isTrue()) {
                titles.add(row.title());
            }
        }
        return titles;
    }

    private static EffectiveScope scope(
            DefaultScope defaultScope,
            List<EffectiveScope.Contribution> scopes,
            List<EffectiveScope.Contribution> filters) {

        return new EffectiveScope("widgets", defaultScope, scopes, filters, false);
    }

    /**
     * A source-declared scope for one role. Design-time rather than runtime so
     * that the test needs no compiler and no database — and because it also
     * exercises the path where the two layers land in the same list.
     */
    private static EffectiveScope.Contribution matching(String roleCode, boolean matchTodo) {
        return new EffectiveScope.Contribution(
                "test", roleCode, "scope for " + roleCode, null, null,
                new EffectiveScope.DesignTime(null, (entity, principal) -> {
                    boolean isTodo = ((Ticket) entity).status() == FixtureStatus.TODO;
                    return Tri.of(isTodo == matchTodo);
                }));
    }

    private static EffectiveScope.Contribution refusing() {
        return new EffectiveScope.Contribution(
                "test", null, "nothing", null, null,
                new EffectiveScope.DesignTime(null, (entity, principal) -> Tri.FALSE));
    }

    private static RowLevelPrincipal holding(String... roleCodes) {
        return new RowLevelPrincipal(1L, null, 7L, Set.of(roleCodes), Set.of());
    }

    private static Ticket ticket(FixtureStatus status) {
        return new Ticket(status.name(), status);
    }
}
