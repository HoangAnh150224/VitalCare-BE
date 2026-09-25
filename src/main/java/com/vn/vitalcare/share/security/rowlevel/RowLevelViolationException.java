package com.vn.vitalcare.share.security.rowlevel;

/**
 * A write whose <em>resulting</em> state falls outside the writer's
 * {@code WITH CHECK} clause.
 *
 * <p>Answered {@code 422}, not {@code 403} and not {@code 404}. The record does
 * exist as far as this caller is concerned and the request is meaningful; what
 * is refused is the state it would leave behind. That is the same status the
 * exception handler already gives a failed bean validation, and it says the
 * same thing — the request was understood, and the content is not acceptable.
 *
 * <p>The message names the <em>field</em> and the <em>value</em> that were
 * refused, and never the policy behind them. That distinction is the whole
 * point: echoing back what the writer just chose tells them which change to
 * undo, while the rule that refused it belongs to whoever may read policies.
 * "The status field puts this record outside your data scope" leaves somebody
 * staring at a form; "your write scope does not allow status to become
 * PUBLISHED" tells them what happened.
 */
public class RowLevelViolationException extends RuntimeException {

    private final String field;

    public RowLevelViolationException(String field) {
        this(field, null);
    }

    public RowLevelViolationException(String field, String value) {
        super(message(field, value));
        this.field = field;
    }

    private static String message(String field, String value) {
        if (field == null) {
            return "This change would put the record outside your data scope";
        }
        if (value == null) {
            return "Cannot save: your data scope does not allow \"%s\" to be empty".formatted(field);
        }
        return "Cannot save: your data scope does not allow \"%s\" to become \"%s\"".formatted(field, value);
    }

    /**
     * The offending field, for a client that wants to highlight it. Null when no
     * single field is to blame.
     */
    public String field() {
        return field;
    }
}
