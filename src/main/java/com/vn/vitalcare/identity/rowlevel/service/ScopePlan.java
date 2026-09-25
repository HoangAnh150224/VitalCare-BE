package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.Op;
import com.vn.vitalcare.share.security.rowlevel.RowLevelContextProvider;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A {@code ScopeTree} after {@code PolicyCompiler} has finished with it:
 * checked, typed, normalised, and holding no free text at all.
 *
 * <p>Two things it guarantees that the authored form does not.
 *
 * <p><b>Every field is a resolved {@link FieldDescriptor} and every value is
 * already the right type.</b> Coercion happened once, when the policy was
 * saved or loaded — not per row, and not per request.
 *
 * <p><b>There is no {@code not} anywhere.</b> Negation was pushed down into the
 * leaves and absorbed into their operators. That is not tidiness; it is the
 * single place this design would otherwise be unsafe. A condition on an
 * association compiles to a correlated {@code EXISTS}, and {@code EXISTS} in
 * SQL is two-valued — it never yields {@code UNKNOWN}. Leave a {@code not}
 * wrapped around one and
 *
 * <pre>
 *   NOT EXISTS (SELECT 1 FROM departments d WHERE d.id = t.department_id AND d.id = ?)
 * </pre>
 *
 * accepts a task whose {@code department_id} is null, because the inner
 * {@code EXISTS} is false and its negation is true. Three-valued semantics say
 * that row is {@code UNKNOWN} and must be refused, so the SQL compiler and the
 * in-memory evaluator would disagree — quietly, and in the direction that shows
 * rows to people who should not see them. After normalisation the {@code not}
 * sits <em>inside</em>:
 *
 * <pre>
 *   EXISTS (SELECT 1 FROM departments d WHERE d.id = t.department_id AND d.id &lt;&gt; ?)
 * </pre>
 *
 * which is empty for a null department, false, and refuses the row. The two
 * compilers agree again.
 */
public sealed interface ScopePlan {

    /** Conjunction. Empty is {@code TRUE} — the full scope. */
    record All(List<ScopePlan> nodes) implements ScopePlan {
        public All {
            nodes = List.copyOf(nodes);
        }
    }

    /** Disjunction. Empty is {@code FALSE} — no rows. */
    record Any(List<ScopePlan> nodes) implements ScopePlan {
        public Any {
            nodes = List.copyOf(nodes);
        }
    }

    /** One comparison, with its field resolved and its operand already coerced. */
    record Leaf(FieldDescriptor field, Op op, Operand operand) implements ScopePlan {
    }

    /** The right-hand side, resolved. */
    sealed interface Operand {

        /** A single constant, already of the field's type. */
        record Literal(Object value) implements Operand {
        }

        /** A set of constants, for {@code in} and {@code notIn}. */
        record Literals(List<Object> values) implements Operand {
        }

        /** A context key, bound to the provider that answers it. */
        record Context(RowLevelContextProvider provider) implements Operand {
        }

        /** Nothing at all — {@code isNull} and {@code isNotNull} take no operand. */
        record None() implements Operand {
        }
    }

    /** The full scope. */
    static ScopePlan unrestricted() {
        return new All(List.of());
    }

    /** No rows at all. */
    static ScopePlan nothing() {
        return new Any(List.of());
    }

    /** True when this plan admits every row without looking at it. */
    default boolean isUnrestricted() {
        return this instanceof All all && all.nodes().isEmpty();
    }

    /**
     * A readable rendering, for {@code /explain} and for the audit trail.
     *
     * <p>Deliberately the compiled form rather than the authored JSON, so that
     * what is shown is what actually runs — including the normalisation, which
     * is the part most likely to surprise whoever wrote the policy.
     */
    default String describe() {
        return switch (this) {
            case All all -> all.nodes().isEmpty()
                    ? "everything"
                    : all.nodes().stream().map(ScopePlan::describe)
                            .collect(Collectors.joining(" AND ", "(", ")"));
            case Any any -> any.nodes().isEmpty()
                    ? "nothing"
                    : any.nodes().stream().map(ScopePlan::describe)
                            .collect(Collectors.joining(" OR ", "(", ")"));
            case Leaf leaf -> "%s %s%s".formatted(
                    leaf.field().policyPath(),
                    leaf.op().code(),
                    switch (leaf.operand()) {
                        case Operand.None ignored -> "";
                        case Operand.Literal literal -> " " + literal.value();
                        case Operand.Literals literals -> " " + literals.values();
                        case Operand.Context context -> " " + context.provider().key();
                    });
        };
    }
}
