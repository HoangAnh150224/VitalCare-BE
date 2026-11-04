package com.vn.vitalcare.share.exception;

/**
 * A request that is well-formed but fails a rule only the service can check,
 * attributed to the one field the person has to change.
 *
 * <p>Answered exactly like a failed bean validation — 422 with
 * {@code errors: {field: message}} — so a form shows it under the field
 * whichever layer caught it.
 */
public class FieldValidationException extends RuntimeException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
