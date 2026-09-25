package com.vn.vitalcare.identity.auth.service;

import com.vn.vitalcare.share.messaging.PostgresEventBus;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Carries {@link AuthoritiesChanged} to the other nodes, and brings theirs back.
 *
 * <p>{@link AuthorityCache} lives in one JVM's memory, so a permission edited on
 * one node would otherwise leave every other node serving what it cached. This
 * is the piece that closes that; the transport itself is
 * {@link PostgresEventBus}.
 *
 * <p>Nothing here is needed for this node to be correct — the cache has already
 * evicted by the time this runs, from the same event. If the bus is down, the
 * other nodes fall back to the cache's expiry: stale for up to a minute rather
 * than indefinitely.
 */
@Component
public class AuthorityChangeChannel {

    private static final Logger log = LoggerFactory.getLogger(AuthorityChangeChannel.class);

    /** What this feature's messages are labelled with on the shared bus. */
    static final String KIND = "auth";

    private static final String EVERYONE = "all";
    private static final String ONE_USER = "user";

    private final PostgresEventBus bus;
    private final ApplicationEventPublisher events;

    public AuthorityChangeChannel(PostgresEventBus bus, ApplicationEventPublisher events) {
        this.bus = bus;
        this.events = events;
    }

    /**
     * Tells the other nodes about a change made on this one.
     *
     * <p>Ordered after {@link AuthorityCache}'s listener so this node has
     * already dropped what it cached before it announces anything: a node
     * should never be the last to know about its own change.
     */
    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void broadcast(AuthoritiesChanged change) {
        bus.publish(KIND, switch (change) {
            case AuthoritiesChanged.ForUser forUser -> ONE_USER + ":" + forUser.userId();
            case AuthoritiesChanged.ForEveryone ignored -> EVERYONE;
        });
    }

    /** Turns another node's message back into the event the cache listens for. */
    @EventListener
    public void onRemote(PostgresEventBus.RemoteEvent remote) {
        if (!KIND.equals(remote.kind())) {
            return;
        }

        String[] parts = remote.payload().split(":");
        AuthoritiesChanged change = switch (parts[0]) {
            case EVERYONE -> new AuthoritiesChanged.ForEveryone();
            case ONE_USER -> parts.length < 2 ? null : forUser(parts[1]);
            default -> null;
        };

        if (change == null) {
            log.warn("Ignoring unrecognised authority message: {}", remote.payload());
            return;
        }
        events.publishEvent(new Received(change));
    }

    private static AuthoritiesChanged forUser(String id) {
        try {
            return new AuthoritiesChanged.ForUser(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A change that happened on another node.
     *
     * <p>Distinct from {@link AuthoritiesChanged} so the cache can evict on it
     * without this class re-broadcasting what it just received.
     */
    public record Received(AuthoritiesChanged change) {
    }
}
