package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.security.AuthoritiesChanged;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPoliciesChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the auth domain that a policy edit has moved everybody's stamp.
 *
 * <p>The row-level generation is folded into {@code X-Auth-Version}, so a policy
 * change makes every account's stamp stale — but the authority cache computed
 * those stamps with the <em>old</em> generation and is holding them. Without
 * this, an open tab would go on believing its cached permissions were current.
 *
 * <p>Republishing as {@link AuthoritiesChanged.ForEveryone} means both existing
 * paths do their usual work with nothing new to build: the cache drops what it
 * holds, and the live channel pushes each connected account its grants — which
 * now carry the new scoped-resource list too.
 *
 * <p><b>{@code @Order(2)} is load-bearing.</b> It runs after
 * {@link RowLevelPolicyCache} at {@code 0} and {@link RowLevelPolicyChannel} at
 * {@code 1}, both listening to the same event. Going first would push grants
 * whose stamp was recomputed from the policy set that is being replaced.
 *
 * <p>Blunt on purpose, in the same way and for the same reason as
 * {@code AuthoritiesChanged.ForEveryone} itself. Working out whose scope
 * actually moved would mean reading the user domain's table from in here; a
 * policy edit is a rare administrative act, and the cost is one lookup per
 * active account on its next request.
 */
@Component
public class RowLevelAuthoritySync {

    private final ApplicationEventPublisher events;

    public RowLevelAuthoritySync(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Order(2)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPoliciesChanged(RowLevelPoliciesChanged change) {
        // Published outside a transaction, which is exactly the case
        // fallbackExecution exists for on the listeners that receive it.
        events.publishEvent(new AuthoritiesChanged.ForEveryone());
    }
}
