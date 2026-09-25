package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.Op;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import com.vn.vitalcare.share.security.rowlevel.Tri;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Component;

/**
 * Answers the same question as {@link PredicateCompiler}, against an entity
 * already in memory. No query is issued, ever.
 *
 * <p>Exactly three callers, and the list is closed on purpose:
 *
 * <ol>
 *   <li>{@code WITH CHECK} — is the state a write is about to leave behind
 *       still inside the writer's scope?</li>
 *   <li>The {@code _can} flags on each row the API returns.</li>
 *   <li>Masking an embedded summary in a payload.</li>
 * </ol>
 *
 * <p><b>It is never the filter for a list.</b> That is SQL's job. Using this
 * instead would be an N+1 multiplied by the number of rows, and it would still
 * be wrong, because a page of ten filtered afterwards is not the first ten
 * matching rows.
 *
 * <p>Everything here implements the three-valued rules of {@link Tri}
 * deliberately, because Java would otherwise supply two-valued answers and the
 * two compilers would part company on nulls. The single rule: a row is accepted
 * only on {@link Tri#TRUE}.
 */
@Component
public class EntityEvaluator {

    public Tri evaluate(ScopePlan plan, Object entity, RowLevelPrincipal principal) {
        return switch (plan) {
            case ScopePlan.All all -> {
                Tri result = Tri.TRUE;
                for (ScopePlan node : all.nodes()) {
                    result = result.and(evaluate(node, entity, principal));
                    if (result == Tri.FALSE) {
                        yield Tri.FALSE;
                    }
                }
                yield result;
            }
            case ScopePlan.Any any -> {
                Tri result = Tri.FALSE;
                for (ScopePlan node : any.nodes()) {
                    result = result.or(evaluate(node, entity, principal));
                    if (result == Tri.TRUE) {
                        yield Tri.TRUE;
                    }
                }
                yield result;
            }
            case ScopePlan.Leaf leaf -> leaf(leaf, entity, principal);
        };
    }

    /**
     * The first leaf that did not come out {@link Tri#TRUE}, for the message on
     * a refused write.
     *
     * <p>Depth-first over the plan, not a re-run of the logic: it names a field
     * that contributed to the refusal, which is what somebody looking at a form
     * needs. It deliberately does not explain the policy — a field name is a
     * hint about what to change; the policy is somebody else's business.
     */
    public Optional<ScopePlan.Leaf> firstFailure(ScopePlan plan, Object entity, RowLevelPrincipal principal) {
        // Nothing here failed, so nothing here is to blame. Checking the node
        // before descending is what keeps this from naming a leaf inside an
        // `any` that the sibling beside it already satisfied -- a message that
        // points at a field the writer never touched is worse than no message.
        if (evaluate(plan, entity, principal).isTrue()) {
            return Optional.empty();
        }

        return switch (plan) {
            case ScopePlan.Leaf leaf -> Optional.of(leaf);
            // Only the children that actually failed. In an `all`, one is
            // enough to sink the group and the rest may be fine.
            case ScopePlan.All all -> first(all.nodes(), entity, principal);
            // The group is false, so every child is. The first is as good an
            // explanation as any, and it is the one written first.
            case ScopePlan.Any any -> first(any.nodes(), entity, principal);
        };
    }

    private Optional<ScopePlan.Leaf> first(List<ScopePlan> nodes, Object entity, RowLevelPrincipal principal) {
        for (ScopePlan node : nodes) {
            Optional<ScopePlan.Leaf> failure = firstFailure(node, entity, principal);
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
    }

    private Tri leaf(ScopePlan.Leaf leaf, Object entity, RowLevelPrincipal principal) {
        Object actual = read(entity, leaf.field());

        // The two operators that are never UNKNOWN: they are asking about the
        // null itself, so a null is an answer rather than an absence of one.
        if (leaf.op() == Op.IS_NULL) {
            return Tri.of(actual == null);
        }
        if (leaf.op() == Op.IS_NOT_NULL) {
            return Tri.of(actual != null);
        }

        // Checked before the operand, and before the empty-set rules below.
        // "This row has no department" is not a value that can be compared with
        // anything, whatever it is being compared against.
        if (actual == null) {
            return Tri.UNKNOWN;
        }

        Object expected = operand(leaf, principal);
        if (expected == null) {
            return Tri.UNKNOWN;
        }

        return switch (leaf.op()) {
            case EQ -> Tri.of(equals(actual, expected));
            case NE -> Tri.of(!equals(actual, expected));
            case IN -> contains(expected, actual);
            case NOT_IN -> contains(expected, actual).negate();
            case LT -> compare(actual, expected, result -> result < 0);
            case LTE -> compare(actual, expected, result -> result <= 0);
            case GT -> compare(actual, expected, result -> result > 0);
            case GTE -> compare(actual, expected, result -> result >= 0);
            case LIKE -> Tri.of(like(actual, expected));
            case NOT_LIKE -> Tri.of(!like(actual, expected));
            // Expanded by the compiler; the nullary pair was handled above.
            case BETWEEN, IS_NULL, IS_NOT_NULL -> throw new IllegalStateException(
                    "Unhandled operator " + leaf.op());
        };
    }

    /**
     * The value a leaf is about.
     *
     * <p>A reference reads the association and then its id, so a null
     * association yields null rather than throwing — which is precisely the
     * case the three-valued rules are for. It also means {@code isNull} on
     * {@code department.id} asks the same question the SQL side asks of the
     * foreign key.
     */
    private Object read(Object entity, FieldDescriptor field) {
        BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(entity);
        Object value = wrapper.getPropertyValue(field.attribute());

        if (field.kind() != FieldDescriptor.Kind.REFERENCE || value == null) {
            return value;
        }
        return PropertyAccessorFactory.forBeanPropertyAccess(value).getPropertyValue("id");
    }

    private Object operand(ScopePlan.Leaf leaf, RowLevelPrincipal principal) {
        return switch (leaf.operand()) {
            case ScopePlan.Operand.None ignored -> null;
            case ScopePlan.Operand.Literal literal -> literal.value();
            case ScopePlan.Operand.Literals literals -> literals.values();
            case ScopePlan.Operand.Context context -> context.provider().resolve(principal);
        };
    }

    /**
     * Set membership, three-valued.
     *
     * <p>The empty cases are definitions, not consequences: {@code in} over
     * nothing is false and {@code notIn} over nothing is true, so a context
     * provider answering "this person manages no departments" produces "sees
     * nothing" rather than a condition that cannot be expressed. The field
     * having already been checked for null above is what keeps this agreeing
     * with the SQL side, where an empty {@code notIn} compiles to
     * {@code IS NOT NULL}.
     */
    private Tri contains(Object expected, Object actual) {
        Collection<?> values = expected instanceof Collection<?> collection ? collection : List.of(expected);
        if (values.isEmpty()) {
            return Tri.FALSE;
        }
        for (Object candidate : values) {
            if (equals(actual, candidate)) {
                return Tri.TRUE;
            }
        }
        return Tri.FALSE;
    }

    /**
     * Equality that survives the width a value happened to arrive in.
     *
     * <p>An id read off an entity is a {@code Long}; a context provider might
     * hand back an {@code Integer}; a literal was coerced to the field's own
     * type. {@code Objects.equals} would call two of those different, and the
     * database would not — which is the two compilers disagreeing again, just
     * over numbers rather than nulls.
     */
    private boolean equals(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        return Objects.equals(left, right);
    }

    private Tri compare(Object actual, Object expected, java.util.function.IntPredicate accept) {
        if (actual instanceof Number a && expected instanceof Number b) {
            return Tri.of(accept.test(decimal(a).compareTo(decimal(b))));
        }
        if (actual instanceof Comparable<?> && actual.getClass().isInstance(expected)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) actual;
            return Tri.of(accept.test(comparable.compareTo(expected)));
        }
        // Two values that cannot be ordered against each other is a question
        // with no answer, which is what UNKNOWN means -- and therefore a
        // refusal rather than a crash on a path that runs per row.
        return Tri.UNKNOWN;
    }

    private boolean like(Object actual, Object expected) {
        return actual.toString().toLowerCase(Locale.ROOT)
                .contains(expected.toString().toLowerCase(Locale.ROOT));
    }

    private static BigDecimal decimal(Number number) {
        return number instanceof BigDecimal value ? value : new BigDecimal(number.toString());
    }
}
