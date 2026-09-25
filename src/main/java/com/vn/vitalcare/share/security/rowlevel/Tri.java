package com.vn.vitalcare.share.security.rowlevel;

/**
 * Three-valued logic, specified rather than inherited.
 *
 * <p>This enum is what keeps the two compilers from disagreeing. SQL evaluates
 * a {@code WHERE} clause in three values and drops a row whose predicate is
 * {@code NULL}; Java's {@code &&} has only two. Left to its own devices the
 * in-memory evaluator would answer {@code false == null} with {@code false} in
 * some places and {@code true} in others, and a row visible in a list would be
 * refused on save — or, far worse, the other way round.
 *
 * <p><b>The rule, at both compilers:</b> a row is accepted <em>only</em> when
 * the result is {@link #TRUE}. {@link #UNKNOWN} is a refusal. That is already
 * what SQL does, so {@code PredicateCompiler} gets it for free and
 * {@code EntityEvaluator} has to implement it.
 *
 * <p>The consequence worth telling users about: {@code not(assignee.id = 42)}
 * does <em>not</em> match a task with no assignee. Wanting that means writing
 * the {@code isNull} branch out, and the policy builder in the admin screen
 * offers it whenever a {@code not} is put on a nullable field.
 */
public enum Tri {

    TRUE,
    FALSE,
    UNKNOWN;

    /** Kleene AND: {@code FALSE} wins over everything, then {@code UNKNOWN}. */
    public Tri and(Tri other) {
        if (this == FALSE || other == FALSE) {
            return FALSE;
        }
        return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE;
    }

    /** Kleene OR: {@code TRUE} wins over everything, then {@code UNKNOWN}. */
    public Tri or(Tri other) {
        if (this == TRUE || other == TRUE) {
            return TRUE;
        }
        return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : FALSE;
    }

    /** {@code UNKNOWN} negates to itself — that is the whole difference from boolean logic. */
    public Tri negate() {
        return switch (this) {
            case TRUE -> FALSE;
            case FALSE -> TRUE;
            case UNKNOWN -> UNKNOWN;
        };
    }

    /** The only way a row is ever accepted. */
    public boolean isTrue() {
        return this == TRUE;
    }

    public static Tri of(boolean value) {
        return value ? TRUE : FALSE;
    }
}
