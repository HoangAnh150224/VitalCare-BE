package com.vn.vitalcare.share.security.rowlevel;

/**
 * What a role with no {@code SCOPE} of its own may reach.
 *
 * <p>Declared per resource by {@link RowLevelPolicySet#defaultScope()}, with no
 * implicit default anywhere: this is a conscious choice made at the moment a
 * resource is brought under row-level management, not something that falls out
 * of forgetting to make it.
 */
public enum DefaultScope {

    /**
     * A role with no {@code SCOPE} is not narrowed at all.
     *
     * <p>What makes switching an <em>already running</em> resource on a no-op —
     * nobody loses a row on the day it ships, and no migration has to seed a
     * policy to keep it that way. The cost is that a role somebody forgot to
     * write a policy for is unrestricted rather than empty, which is why the
     * coverage matrix marks every such cell red and {@code /explain} says so
     * outright instead of leaving it to be discovered.
     */
    FULL,

    /**
     * A role with no {@code SCOPE} sees nothing.
     *
     * <p>The recommendation for a new resource, and the destination for every
     * resource once its roles all have policies. A forgotten policy shows up as
     * an empty screen somebody reports within the hour, rather than as data
     * quietly reaching people it should not.
     */
    NONE
}
