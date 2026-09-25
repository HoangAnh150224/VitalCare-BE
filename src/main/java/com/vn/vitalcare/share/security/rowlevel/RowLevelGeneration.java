package com.vn.vitalcare.share.security.rowlevel;

/**
 * A short stamp of the policy set currently in force.
 *
 * <p>It exists so the authority cache can fold row-level policy into the
 * {@code X-Auth-Version} stamp it already puts on every response, without
 * depending on the policy feature: the interface lives here, the policy cache
 * implements it, and the dependency runs one way. Reversing it would be the
 * cycle that forced {@code token/} into a slice of its own.
 *
 * <p>Folding it in rather than adding a header is what means no new header has
 * to reach {@code Access-Control-Expose-Headers} — and therefore no new way to
 * silently forget one. A policy edit changes the stamp; the next response's
 * header differs from the one the browser holds; the client re-reads its
 * permissions down the path that already existed.
 *
 * <p>The stamp covers the <em>whole</em> policy set rather than the caller's own
 * scope, on purpose. Making it per-account would need the account's roles, which
 * the authority cache is in the middle of building. The trade is that editing
 * one policy sends every signed-in account to re-read permissions once — the
 * same trade this codebase already made for
 * {@code AuthoritiesChanged.ForEveryone}, and for the same reason: a policy edit
 * is a rare administrative act.
 */
public interface RowLevelGeneration {

    /** A value that changes when, and only when, the enabled policy set changes. */
    String generation();
}
