package com.vn.vitalcare.share.security.rowlevel;

import java.util.List;

/**
 * A condition, as a runtime policy stores it: data, not a program.
 *
 * <pre>
 *   Node  := { "all": [Node...] }        // AND
 *          | { "any": [Node...] }        // OR
 *          | { "not": Node }
 *          | Leaf
 *   Leaf  := { "field": path, "op": operator, "value": Value? }
 *   Value := { "lit": &lt;JSON constant&gt; } | { "ctx": &lt;registered key&gt; }
 * </pre>
 *
 * <p>{@code {"all": []}} is the empty tree: always true, which is to say the
 * full scope.
 *
 * <p>No branch of this grammar can call a method, construct a type or name a
 * bean. There is nothing here to inject into, which is what makes storing
 * policies in a table — editable from an admin screen by a person — a
 * configuration change rather than a remote code execution surface.
 *
 * <p>This is the <em>authored</em> form. {@code PolicyCompiler} turns it into a
 * {@code ScopePlan}, which is checked, typed, normalised, and the only thing
 * either compiler ever sees.
 */
public sealed interface ScopeTree {

    /** Conjunction. Empty means true — the full scope. */
    record All(List<ScopeTree> nodes) implements ScopeTree {
        public All {
            nodes = List.copyOf(nodes);
        }
    }

    /** Disjunction. Empty means false — no rows. */
    record Any(List<ScopeTree> nodes) implements ScopeTree {
        public Any {
            nodes = List.copyOf(nodes);
        }
    }

    /** Negation, in the authored form only: the compiler pushes it down to the leaves. */
    record Not(ScopeTree node) implements ScopeTree {
    }

    /**
     * One comparison.
     *
     * @param value {@code null} for {@code isNull} and {@code isNotNull}, which
     *              take no operand — the grammar must not force one onto an
     *              operator that has none
     */
    record Leaf(String field, Op op, Value value) implements ScopeTree {
    }

    /** The right-hand side of a leaf. */
    sealed interface Value {

        /** A constant written into the policy. Coerced to the field's type when the policy is saved. */
        record Lit(Object value) implements Value {
        }

        /** A registered context key, resolved per request from {@link RowLevelPrincipal}. */
        record Ctx(String key) implements Value {
        }
    }

    /** The empty tree — always true. */
    static ScopeTree unrestricted() {
        return new All(List.of());
    }
}
