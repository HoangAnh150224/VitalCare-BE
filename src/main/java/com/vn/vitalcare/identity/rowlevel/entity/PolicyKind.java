package com.vn.vitalcare.identity.rowlevel.entity;

/**
 * The two kinds of row-level policy, which differ in what they attach to — and
 * therefore in how they combine.
 *
 * <p>This is the central decision of the whole feature. Everything else follows
 * from it.
 */
public enum PolicyKind {

    /**
     * Attached to one role. Says which rows that role can reach.
     *
     * <p>Combined with {@code OR} across the roles an account holds, because
     * roles in this system are additive: the authority cache already unions
     * their permission codes, so their data scopes have to union too. A team
     * lead holding both {@code MANAGER} (their department's work) and
     * {@code USER} (their own work) must see both. Combining with {@code AND}
     * would mean that adding a role <em>removes</em> rows, which nobody
     * predicts and no administrator can reason about.
     */
    SCOPE,

    /**
     * Attached to a resource, and to nobody in particular. Says which rows are
     * out of bounds for everyone.
     *
     * <p>Always intersected, never unioned. And deliberately not attached to a
     * role: a prohibition tied to a role is escaped by not holding that role,
     * and a role created tomorrow would not carry it at all.
     */
    FILTER
}
