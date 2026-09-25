package com.vn.vitalcare.share.security.rowlevel;

import java.util.Locale;
import java.util.Map;

/**
 * The closed set of comparisons a runtime policy may express.
 *
 * <p>Closed is the point. There is no expression language at runtime — a policy
 * is data, and this enum is the whole of what that data can say — so there is
 * nothing to inject into, nothing to sandbox, and no switch to turn a sandbox
 * off with.
 *
 * <p>{@link #negate()} is not decoration: it is the lookup that lets
 * {@code PolicyCompiler} push every {@code not} down to the leaves. See
 * {@code ScopePlan} for why a {@code not} left at an inner node is a security
 * bug rather than a style question.
 */
public enum Op {

    EQ("eq", 1),
    NE("ne", 1),
    IN("in", -1),
    NOT_IN("notIn", -1),
    LT("lt", 1),
    LTE("lte", 1),
    GT("gt", 1),
    GTE("gte", 1),
    /** Expanded to {@code all[gte, lte]} by the compiler, so nothing downstream sees it. */
    BETWEEN("between", 2),
    LIKE("like", 1),
    NOT_LIKE("notLike", 1),
    IS_NULL("isNull", 0),
    IS_NOT_NULL("isNotNull", 0);

    private static final Map<Op, Op> NEGATIONS = Map.ofEntries(
            Map.entry(EQ, NE),
            Map.entry(NE, EQ),
            Map.entry(IN, NOT_IN),
            Map.entry(NOT_IN, IN),
            Map.entry(LT, GTE),
            Map.entry(GTE, LT),
            Map.entry(LTE, GT),
            Map.entry(GT, LTE),
            Map.entry(LIKE, NOT_LIKE),
            Map.entry(NOT_LIKE, LIKE),
            Map.entry(IS_NULL, IS_NOT_NULL),
            Map.entry(IS_NOT_NULL, IS_NULL));

    private final String code;
    private final int arity;

    Op(String code, int arity) {
        this.code = code;
        this.arity = arity;
    }

    /** The wire form, as it appears in a policy's JSON. */
    public String code() {
        return code;
    }

    /** How many operands the operator takes; {@code -1} means a set of any size. */
    public int arity() {
        return arity;
    }

    /** True for the two operators that carry no {@code value} at all. */
    public boolean isNullary() {
        return arity == 0;
    }

    public boolean isSetValued() {
        return arity < 0;
    }

    /**
     * The operator that means the opposite, for pushing a {@code not} into a leaf.
     *
     * @throws IllegalStateException for {@link #BETWEEN}, which the compiler
     *                               expands away before normalisation — reaching
     *                               here with one means the expansion was skipped
     */
    public Op negate() {
        Op negated = NEGATIONS.get(this);
        if (negated == null) {
            throw new IllegalStateException(
                    "between must be expanded to all[gte, lte] before a not can be pushed into it");
        }
        return negated;
    }

    /** Parses the wire form. Case-insensitive on the first letter only — {@code notIn}, not {@code notin}. */
    public static Op from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An operator is required");
        }
        String trimmed = value.trim();
        for (Op candidate : values()) {
            if (candidate.code.equalsIgnoreCase(trimmed)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unknown operator \"%s\"".formatted(trimmed.toLowerCase(Locale.ROOT)));
    }
}
