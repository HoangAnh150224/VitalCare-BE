package com.vn.vitalcare.share.security.rowlevel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * One field a policy is allowed to mention, and everything needed to check that
 * it mentions it correctly.
 *
 * <p>The set of fields is closed and declared away from the entity, by
 * {@link RowLevelPolicySet#fields()}. An entity is a data model; which of its
 * columns may carry a security decision is a separate question, and answering
 * it in a separate place is what stops a new column becoming policy surface the
 * moment somebody adds it.
 *
 * <p>Four properties, all four load-bearing. {@code path} is how the value is
 * reached. {@code type} coerces a literal <em>when the policy is saved</em>,
 * not when it runs. {@code nullable} decides whether {@code isNull} and
 * {@code isNotNull} are legal, and whether a {@code not} needs the warning
 * about three-valued logic. {@code kind} decides the shape of the generated
 * SQL: a {@code REFERENCE} compiles to a correlated {@code EXISTS}, while
 * {@code COLUMN} and {@code ID} compare straight off the root.
 *
 * <h2>References</h2>
 *
 * <p>A reference declared as {@code reference("department", Department.class)}
 * is written in a policy as {@code department.id} — the association, then the
 * target's identifier. That is the only shape a reference takes: the identifier
 * is what a scope is ever about, and allowing arbitrary paths into the target
 * would mean type-checking a graph rather than a field. A condition on
 * something further away is a design-time policy, where a {@code Specification}
 * can say anything Java can.
 */
public record FieldDescriptor(
        String path,
        Class<?> type,
        boolean nullable,
        Kind kind,
        Set<Op> operators) {

    public enum Kind {
        /** A scalar on the entity itself. */
        COLUMN,
        /** A to-one association. Written as {@code name.id}; compiled to {@code EXISTS}. */
        REFERENCE,
        /** The entity's own identifier. */
        ID
    }

    public FieldDescriptor {
        operators = Set.copyOf(operators);
    }

    /** The attribute on the entity: {@code department} for a reference, the path itself otherwise. */
    public String attribute() {
        return path;
    }

    /** How a policy writes this field: {@code status}, or {@code department.id}. */
    public String policyPath() {
        return kind == Kind.REFERENCE ? path + ".id" : path;
    }

    /** The type a literal is coerced to. A reference compares by id, so that is {@code Long}. */
    public Class<?> comparableType() {
        return kind == Kind.REFERENCE ? Long.class : type;
    }

    /** A nullable scalar on the entity. */
    public static FieldDescriptor column(String path, Class<?> type) {
        return column(path, type, true);
    }

    public static FieldDescriptor column(String path, Class<?> type, boolean nullable) {
        return new FieldDescriptor(path, type, nullable, Kind.COLUMN, operatorsFor(type, nullable));
    }

    /**
     * A to-one association, addressed by the target's id.
     *
     * <p>Always nullable: a to-one foreign key that happens to be
     * {@code NOT NULL} today loses nothing by allowing {@code isNull}, and
     * assuming otherwise is how a policy quietly stops matching when the column
     * is later relaxed.
     */
    public static FieldDescriptor reference(String path, Class<?> target) {
        return new FieldDescriptor(path, target, true, Kind.REFERENCE, operatorsFor(Long.class, true));
    }

    /** The entity's own identifier. Never null on a persisted row. */
    public static FieldDescriptor id(String path) {
        return new FieldDescriptor(path, Long.class, false, Kind.ID, operatorsFor(Long.class, false));
    }

    /**
     * Which comparisons make sense for a type.
     *
     * <p>Ordering operators only where ordering means something, and
     * {@code like} only on text. Offering {@code gte} on an enum would look
     * like it worked and silently compare the stored names alphabetically —
     * the same trap that keeps {@code status} and {@code priority} out of the
     * task list's sortable set.
     */
    private static Set<Op> operatorsFor(Class<?> type, boolean nullable) {
        EnumSet<Op> ops = EnumSet.of(Op.EQ, Op.NE, Op.IN, Op.NOT_IN);

        if (CharSequence.class.isAssignableFrom(type)) {
            ops.add(Op.LIKE);
            ops.add(Op.NOT_LIKE);
        }
        if (isOrdered(type)) {
            ops.add(Op.LT);
            ops.add(Op.LTE);
            ops.add(Op.GT);
            ops.add(Op.GTE);
            ops.add(Op.BETWEEN);
        }
        if (nullable) {
            ops.add(Op.IS_NULL);
            ops.add(Op.IS_NOT_NULL);
        }
        return Set.copyOf(ops);
    }

    private static boolean isOrdered(Class<?> type) {
        return Number.class.isAssignableFrom(type)
                || type == LocalDate.class
                || type == LocalDateTime.class
                || type == Instant.class;
    }
}
