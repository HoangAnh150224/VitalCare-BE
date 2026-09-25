package com.vn.vitalcare.share.security.rowlevel;

import java.util.Locale;

/**
 * The three things a policy can be written about.
 *
 * <p>Exactly the three the resource-permission layer already collapses Refine's
 * six actions into — {@code list}/{@code show} to {@code read},
 * {@code create}/{@code edit}/{@code clone} to {@code write}, {@code delete} to
 * {@code delete}. One vocabulary across both layers, so a policy's action is
 * the same word as the second half of the permission code that had to pass
 * before this layer was ever reached.
 */
public enum Action {

    /** Which rows are visible: list, show and getMany. Contributes a {@code USING} clause. */
    READ,

    /** Which rows may be written, and which end states are allowed. Both {@code USING} and {@code WITH CHECK}. */
    WRITE,

    /** Which rows may be removed. {@code USING} only — there is no new state to check. */
    DELETE;

    /** The lower-case form stored in {@code row_level_policy.action} and sent on the wire. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the wire form, case-insensitively.
     *
     * @throws IllegalArgumentException on anything that is not one of the three
     */
    public static Action from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An action is required (read, write or delete)");
        }
        return Action.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
