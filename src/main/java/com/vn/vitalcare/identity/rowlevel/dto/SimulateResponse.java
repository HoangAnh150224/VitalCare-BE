package com.vn.vitalcare.identity.rowlevel.dto;

/**
 * The answer, in three values rather than two.
 *
 * <p>{@code UNKNOWN} is deliberately not collapsed into {@code false}, because
 * it is both the commonest answer to "why can I not see this record" and the
 * hardest to guess at. It almost always means a null foreign key turning a
 * whole branch unknown, and unknown is a refusal.
 *
 * @param failedAt the rule that did not come out true, in its compiled form
 * @param because  the reason in words, usually naming the null
 */
public record SimulateResponse(
        boolean allowed,
        String evaluatedAs,
        String failedAt,
        String because) {
}
