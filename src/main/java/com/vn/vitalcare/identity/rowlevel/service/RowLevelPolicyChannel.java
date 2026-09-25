package com.vn.vitalcare.identity.rowlevel.service;

import com.vn.vitalcare.share.messaging.PostgresEventBus;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPoliciesChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Carries {@link RowLevelPoliciesChanged} to the other nodes, and brings theirs
 * back — the same shape as {@code AuthorityChangeChannel}, over the same bus.
 *
 * <p>{@link RowLevelPolicyCache} lives in one JVM's memory, so a policy edited
 * on one node would otherwise leave every other node filtering by what it
 * cached. Nothing here is needed for <em>this</em> node to be correct: its cache
 * has already reloaded from the same event. If the bus is down, the other nodes
 * fall back to the cache's expiry — stale for up to a minute rather than
 * indefinitely.
 */
@Component
public class RowLevelPolicyChannel {

    /** What this feature's messages are labelled with on the shared bus. */
    static final String KIND = "rls";

    private static final String CHANGED = "changed";

    private final PostgresEventBus bus;
    private final ApplicationEventPublisher events;

    public RowLevelPolicyChannel(PostgresEventBus bus, ApplicationEventPublisher events) {
        this.bus = bus;
        this.events = events;
    }

    /**
     * Ordered after the cache's own listener, so this node has already applied
     * the change before it announces it. A node should never be the last to know
     * about its own change.
     */
    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void broadcast(RowLevelPoliciesChanged change) {
        bus.publish(KIND, CHANGED);
    }

    /** Turns another node's message back into the event the cache listens for. */
    @EventListener
    public void onRemote(PostgresEventBus.RemoteEvent remote) {
        if (!KIND.equals(remote.kind())) {
            return;
        }
        events.publishEvent(new Received());
    }

    /**
     * A change that happened on another node.
     *
     * <p>A distinct type from {@link RowLevelPoliciesChanged} on purpose. If the
     * cache could not tell the two apart it would re-broadcast what it had just
     * received, and two nodes would notify each other forever.
     */
    public record Received() {
    }
}
