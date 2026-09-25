package com.vn.vitalcare.share.security;

/**
 * Something happened that changes what somebody may do.
 *
 * <p>Published by the features that own the change — {@code UserService} when
 * an account's roles or status move, {@code RoleService} when a role's grants
 * do — and consumed by the {@code auth} slice, which caches authorities and has
 * to drop what it cached.
 *
 * <p>An event rather than a method call, and that is the whole point. The cache
 * needs {@code UserService} to populate itself; if {@code UserService} in turn
 * needed the cache to evict it, Spring would refuse to construct either. An
 * event leaves no compile-time edge in the other direction, so the two features
 * stay independent while still being able to tell each other something.
 *
 * <p>Published from inside the transaction that makes the change, and consumed
 * with {@code @TransactionalEventListener(AFTER_COMMIT)}: evicting before the
 * commit would let a concurrent request re-populate the cache with the very
 * rows that are about to change.
 */
public sealed interface AuthoritiesChanged {

    /**
     * One account's own grants changed — its roles, its status, or it was
     * deleted.
     */
    record ForUser(long userId) implements AuthoritiesChanged {
    }

    /**
     * A role changed, so everyone holding it is affected.
     *
     * <p>Deliberately not "everyone holding role X". Working out who that is
     * means reading the user feature's table from inside the role feature,
     * which is exactly the coupling this layout exists to prevent. Editing a
     * role is a rare administrative act, and the cost of dropping everything is
     * one lookup per active user on their next request — cheaper than the
     * coupling, and far easier to reason about than a partial eviction that is
     * subtly wrong.
     */
    record ForEveryone() implements AuthoritiesChanged {
    }
}
