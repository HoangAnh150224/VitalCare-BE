package com.vn.vitalcare.share.security.rowlevel;

/**
 * Supplies one context value — the right-hand side of a leaf written as
 * {@code {"ctx": "user.departmentId"}}.
 *
 * <p>A bean <em>volunteers</em> a key rather than being looked up by name out
 * of the application context: the set of things a policy can refer to is a
 * registry the application builds on purpose, not everything that happens to be
 * on the classpath. Adding an application-specific key is one more bean, and
 * the engine does not change.
 */
public interface RowLevelContextProvider {

    /** The key a policy writes. Unique across the application; a clash refuses to start. */
    String key();

    /**
     * The type of what {@link #resolve} returns, so that a literal on the other
     * side of a comparison can be type-checked <em>when the policy is saved</em>
     * rather than when it runs.
     *
     * <p>For a key that yields a collection, this is the element type.
     */
    Class<?> type();

    /** True when this key yields a set rather than a single value. */
    default boolean isCollection() {
        return false;
    }

    /**
     * The value for this request.
     *
     * <p>Must not query the database. Every one of these runs on the path of
     * every list, so a query here is a query per request per key — the cost
     * this whole design exists to avoid. Everything the built-in keys need is
     * already on {@link RowLevelPrincipal}, which itself came out of a cache.
     *
     * <p>May return {@code null}, which makes the leaf {@link Tri#UNKNOWN} and
     * therefore refuses the row.
     */
    Object resolve(RowLevelPrincipal principal);
}
