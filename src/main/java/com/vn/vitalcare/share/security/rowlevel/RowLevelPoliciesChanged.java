package com.vn.vitalcare.share.security.rowlevel;

/**
 * Somebody edited the row-level policy set.
 *
 * <p>Published by the policy feature from inside the transaction that makes the
 * change, consumed by the cache with
 * {@code @TransactionalEventListener(AFTER_COMMIT)}, and relayed to the other
 * nodes over {@code PostgresEventBus} — the same shape, and for the same
 * reasons, as {@code AuthoritiesChanged}. It lives in {@code shared/} because
 * the publisher and the consumer are different slices and neither should
 * compile against the other.
 *
 * <p>Deliberately carries nothing. The cache holds the whole (small) policy set
 * compiled into one immutable snapshot and invalidates it by swapping a
 * pointer, so there is no partial eviction to get subtly wrong — and therefore
 * nothing useful for this event to say beyond "again".
 */
public sealed interface RowLevelPoliciesChanged {

    record Changed() implements RowLevelPoliciesChanged {
    }
}
