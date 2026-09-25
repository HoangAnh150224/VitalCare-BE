package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.Op;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPrincipal;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Compiles a {@link ScopePlan} into the {@code WHERE} clause of the query that
 * is being run anyway. This is the {@code USING} half: what the caller can see.
 *
 * <h2>Everything off the root becomes an EXISTS. No joins are generated.</h2>
 *
 * <p>Four things come out of that one rule at once.
 *
 * <p><b>There is no inner-versus-left join to get wrong.</b> No join is ever
 * produced by this compiler.
 *
 * <p><b>Rows are not multiplied</b>, so {@code Page.getTotalElements()} and the
 * {@code X-Total-Count} header stay accurate with no {@code distinct} to
 * remember.
 *
 * <p><b>Spring Data's separate count query keeps its meaning</b>, because it
 * carries no join either.
 *
 * <p><b>It maps one-to-one onto {@link EntityEvaluator}.</b> An {@code EXISTS}
 * over a to-one association is exactly "read the association, then compare",
 * which is what the in-memory evaluator does — and the two agreeing is the
 * property this whole design rests on.
 *
 * <p>The cost is a correlated subquery on an indexed foreign key. PostgreSQL
 * generally rewrites that into a semi-join, so the plan is what a join would
 * have produced anyway.
 */
@Component
public class PredicateCompiler {

    /**
     * @param query never null in practice — every {@code JpaSpecificationExecutor}
     *              path passes one — but a subquery cannot be built without it,
     *              so this says so plainly rather than failing later as a null
     *              dereference somewhere less obvious
     */
    public Predicate toPredicate(
            ScopePlan plan,
            Root<?> root,
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            RowLevelPrincipal principal) {

        return switch (plan) {
            case ScopePlan.All all -> all.nodes().isEmpty()
                    ? cb.conjunction()
                    : cb.and(compile(all.nodes(), root, query, cb, principal));
            case ScopePlan.Any any -> any.nodes().isEmpty()
                    ? cb.disjunction()
                    : cb.or(compile(any.nodes(), root, query, cb, principal));
            case ScopePlan.Leaf leaf -> leaf(leaf, root, query, cb, principal);
        };
    }

    private Predicate[] compile(
            List<ScopePlan> nodes,
            Root<?> root,
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            RowLevelPrincipal principal) {

        List<Predicate> predicates = new ArrayList<>(nodes.size());
        for (ScopePlan node : nodes) {
            predicates.add(toPredicate(node, root, query, cb, principal));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private Predicate leaf(
            ScopePlan.Leaf leaf,
            Root<?> root,
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            RowLevelPrincipal principal) {

        FieldDescriptor field = leaf.field();

        if (field.kind() != FieldDescriptor.Kind.REFERENCE) {
            return comparison(cb, root.get(field.attribute()), leaf.op(), value(leaf, principal));
        }

        // "department.id is null" is a question about the association itself,
        // and an EXISTS cannot answer it: a subquery over the target rows can
        // never find one whose own id is null. Asked on the root, it is a
        // foreign key test, which is what was meant.
        if (leaf.op() == Op.IS_NULL) {
            return cb.isNull(root.get(field.attribute()));
        }
        if (leaf.op() == Op.IS_NOT_NULL) {
            return cb.isNotNull(root.get(field.attribute()));
        }

        if (query == null) {
            throw new IllegalStateException(
                    "A row-level policy on the \"%s\" association needs a query to correlate a subquery against"
                            .formatted(field.attribute()));
        }

        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<?> target = subquery.from(field.type());
        subquery.select(cb.literal(1)).where(
                cb.equal(target, root.get(field.attribute())),
                comparison(cb, target.get("id"), leaf.op(), value(leaf, principal)));

        return cb.exists(subquery);
    }

    /**
     * One comparison, in SQL.
     *
     * <p>SQL's own three-valued logic does most of the work: {@code x = ?} with
     * a null {@code x} is {@code NULL}, and {@code WHERE NULL} drops the row —
     * which is exactly the "{@code UNKNOWN} is a refusal" rule. Two cases need
     * writing out, because they are definitions this design chose rather than
     * things SQL can express: an empty {@code in} and an empty {@code notIn}.
     * SQL cannot write {@code x IN ()} at all.
     */
    private Predicate comparison(CriteriaBuilder cb, Path<?> path, Op op, Object value) {
        if (op == Op.IS_NULL) {
            return cb.isNull(path);
        }
        if (op == Op.IS_NOT_NULL) {
            return cb.isNotNull(path);
        }

        // A context key that resolved to nothing -- "this account is in no
        // department" -- makes the comparison UNKNOWN, and UNKNOWN is a
        // refusal. Binding a null parameter would let the database decide, and
        // it does not decide the same way everywhere.
        if (value == null) {
            return cb.disjunction();
        }

        if (op == Op.IN || op == Op.NOT_IN) {
            Collection<?> values = collection(value);
            if (values.isEmpty()) {
                // "In nothing" is no rows. "In nothing" negated is every row --
                // but still not the rows whose field is null, because a null
                // field is UNKNOWN before the operator is even considered.
                return op == Op.IN ? cb.disjunction() : cb.isNotNull(path);
            }
            Predicate in = path.in(values);
            return op == Op.IN ? in : cb.not(in);
        }

        return switch (op) {
            case EQ -> cb.equal(path, value);
            case NE -> cb.notEqual(path, value);
            case LT -> cb.lessThan(comparablePath(path), comparable(value));
            case LTE -> cb.lessThanOrEqualTo(comparablePath(path), comparable(value));
            case GT -> cb.greaterThan(comparablePath(path), comparable(value));
            case GTE -> cb.greaterThanOrEqualTo(comparablePath(path), comparable(value));
            case LIKE -> cb.like(lower(cb, path), pattern(value));
            case NOT_LIKE -> cb.notLike(lower(cb, path), pattern(value));
            // BETWEEN was expanded by the compiler; the two nullary operators
            // and the two set operators were handled above.
            default -> throw new IllegalStateException("Unhandled operator " + op);
        };
    }

    /** The operand for this request: a constant, a set of them, or whatever a context key answers. */
    private Object value(ScopePlan.Leaf leaf, RowLevelPrincipal principal) {
        return switch (leaf.operand()) {
            case ScopePlan.Operand.None ignored -> null;
            case ScopePlan.Operand.Literal literal -> literal.value();
            case ScopePlan.Operand.Literals literals -> literals.values();
            case ScopePlan.Operand.Context context -> context.provider().resolve(principal);
        };
    }

    private static Collection<?> collection(Object value) {
        return value instanceof Collection<?> values ? values : List.of(value);
    }

    private static Expression<String> lower(CriteriaBuilder cb, Path<?> path) {
        return cb.lower(path.as(String.class));
    }

    private static String pattern(Object value) {
        return "%" + value.toString().toLowerCase(Locale.ROOT) + "%";
    }

    @SuppressWarnings("unchecked")
    private static Expression<Comparable<Object>> comparablePath(Path<?> path) {
        return (Expression<Comparable<Object>>) path;
    }

    @SuppressWarnings("unchecked")
    private static Comparable<Object> comparable(Object value) {
        return (Comparable<Object>) value;
    }
}
