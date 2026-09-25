package com.vn.vitalcare.share.security.rowlevel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T2 — the three-valued truth table, exhaustively.
 *
 * <p>Small and boring, and the reason it exists is that everything else rests on
 * it. Java has no {@code UNKNOWN}, so every one of these answers had to be
 * written by hand rather than inherited, and a hand-written truth table is
 * exactly the kind of thing that is wrong in one cell.
 */
class TriTest {

    private static final List<Tri> VALUES = List.of(Tri.TRUE, Tri.FALSE, Tri.UNKNOWN);

    @Test
    @DisplayName("AND: FALSE dominates, then UNKNOWN")
    void and() {
        assertEquals(Tri.TRUE, Tri.TRUE.and(Tri.TRUE));
        assertEquals(Tri.FALSE, Tri.TRUE.and(Tri.FALSE));
        assertEquals(Tri.UNKNOWN, Tri.TRUE.and(Tri.UNKNOWN));

        assertEquals(Tri.FALSE, Tri.FALSE.and(Tri.TRUE));
        assertEquals(Tri.FALSE, Tri.FALSE.and(Tri.FALSE));
        // The one people get wrong: FALSE beats UNKNOWN, because whatever the
        // unknown turns out to be, the conjunction is false either way.
        assertEquals(Tri.FALSE, Tri.FALSE.and(Tri.UNKNOWN));

        assertEquals(Tri.UNKNOWN, Tri.UNKNOWN.and(Tri.TRUE));
        assertEquals(Tri.FALSE, Tri.UNKNOWN.and(Tri.FALSE));
        assertEquals(Tri.UNKNOWN, Tri.UNKNOWN.and(Tri.UNKNOWN));
    }

    @Test
    @DisplayName("OR: TRUE dominates, then UNKNOWN")
    void or() {
        assertEquals(Tri.TRUE, Tri.TRUE.or(Tri.TRUE));
        assertEquals(Tri.TRUE, Tri.TRUE.or(Tri.FALSE));
        // The mirror of the case above, and the one that makes adding a role
        // safe: a scope that cannot be evaluated never takes away a row another
        // scope granted.
        assertEquals(Tri.TRUE, Tri.TRUE.or(Tri.UNKNOWN));

        assertEquals(Tri.TRUE, Tri.FALSE.or(Tri.TRUE));
        assertEquals(Tri.FALSE, Tri.FALSE.or(Tri.FALSE));
        assertEquals(Tri.UNKNOWN, Tri.FALSE.or(Tri.UNKNOWN));

        assertEquals(Tri.TRUE, Tri.UNKNOWN.or(Tri.TRUE));
        assertEquals(Tri.UNKNOWN, Tri.UNKNOWN.or(Tri.FALSE));
        assertEquals(Tri.UNKNOWN, Tri.UNKNOWN.or(Tri.UNKNOWN));
    }

    @Test
    @DisplayName("NOT: UNKNOWN negates to itself, which is the whole difference from boolean logic")
    void negate() {
        assertEquals(Tri.FALSE, Tri.TRUE.negate());
        assertEquals(Tri.TRUE, Tri.FALSE.negate());
        assertEquals(Tri.UNKNOWN, Tri.UNKNOWN.negate());
    }

    @Test
    @DisplayName("Only TRUE accepts a row")
    void onlyTrueAccepts() {
        assertEquals(true, Tri.TRUE.isTrue());
        assertEquals(false, Tri.FALSE.isTrue());
        // The single rule both compilers implement. UNKNOWN is a refusal, which
        // is also what SQL does with a WHERE clause that evaluates to NULL.
        assertEquals(false, Tri.UNKNOWN.isTrue());
    }

    @Test
    @DisplayName("Both operators are commutative and De Morgan holds")
    void kleeneLaws() {
        for (Tri left : VALUES) {
            for (Tri right : VALUES) {
                assertEquals(left.and(right), right.and(left), "and is commutative");
                assertEquals(left.or(right), right.or(left), "or is commutative");

                // The law the whole normalisation pass depends on. If it did not
                // hold in three values, pushing a not down to the leaves would
                // change what a policy means.
                assertEquals(
                        left.and(right).negate(),
                        left.negate().or(right.negate()),
                        "not(a and b) == not a or not b");
                assertEquals(
                        left.or(right).negate(),
                        left.negate().and(right.negate()),
                        "not(a or b) == not a and not b");
            }
        }
    }
}
