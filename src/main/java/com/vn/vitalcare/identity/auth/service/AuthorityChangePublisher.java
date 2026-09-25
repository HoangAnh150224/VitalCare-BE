package com.vn.vitalcare.identity.auth.service;

import com.vn.vitalcare.identity.auth.dto.AuthoritiesResponse;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells an open tab that its permissions moved, without waiting for it to ask.
 *
 * <p>The last gap {@code X-Auth-Version} leaves. That stamp rides on every
 * response, so a client learns of a change on its next request. But a tab
 * sitting idle sends nothing and therefore hears nothing, and now that there
 * is a socket open anyway there is no reason for it to wait.
 *
 * <p>The header is not replaced by this and should not be. It is the path that
 * still works while the socket is reconnecting, or is blocked by a proxy, or
 * was never established. Two mechanisms, one converging on the same client
 * state: whichever arrives first wins and the other becomes a no-op, because
 * both write the same version stamp.
 *
 * <h2>The payload is the grants, not a nudge</h2>
 *
 * <p>Sending "something changed" and letting the client re-read would cost a
 * request per change, which is what the socket exists to avoid. Sending the
 * grants themselves costs nothing extra — they are already in memory, in the
 * cache that was just refilled.
 */
@Component
public class AuthorityChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(AuthorityChangePublisher.class);

    /**
     * Where the client subscribes, minus the {@code /user} prefix Spring adds.
     *
     * <p>Under {@code auth} rather than a resource name because it needs no
     * permission — see {@code StompAuthChannelInterceptor}. Every account may be
     * told what it may do; requiring a permission to receive your own
     * permissions would be circular.
     */
    public static final String DESTINATION = "/queue/auth";

    private final SimpMessagingTemplate messaging;
    private final SimpUserRegistry users;
    private final AuthorizationService authorizationService;

    // The push carries the same payload the endpoint would, which now includes
    // the resources this account is narrowed on. Leaving it out here would make
    // a socket delivery and a fetch leave the client in two different states.
    private final RowLevelSecurity rowLevel;

    public AuthorityChangePublisher(SimpMessagingTemplate messaging,
                                    SimpUserRegistry users,
                                    AuthorizationService authorizationService,
                                    RowLevelSecurity rowLevel) {
        this.messaging = messaging;
        this.users = users;
        this.authorizationService = authorizationService;
        this.rowLevel = rowLevel;
    }

    /**
     * A change made on this node.
     *
     * <p>Ordered last of the three listeners on this event: {@link
     * AuthorityCache} evicts at {@code 0} and {@link AuthorityChangeChannel}
     * tells the other nodes at {@code 1}. Running before the eviction would push
     * the grants that are being replaced.
     */
    @Order(2)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLocalChange(AuthoritiesChanged change) {
        push(change);
    }

    /**
     * A change that reached this node from another one.
     *
     * <p>Each node pushes to its own sessions and only its own, which is what
     * makes this correct across nodes with the in-memory broker.
     */
    @Order(2)
    @EventListener
    public void onRemoteChange(AuthorityChangeChannel.Received received) {
        push(received.change());
    }

    private void push(AuthoritiesChanged change) {
        switch (change) {
            case AuthoritiesChanged.ForUser forUser -> send(String.valueOf(forUser.userId()));
            case AuthoritiesChanged.ForEveryone ignored -> {
                // Everyone holding the edited role is affected, and working out
                // who that is would mean reading the user domain's table from
                // here. Telling every account with a socket open on this node
                // is the same trade the cache makes when it drops everything.
                List<SimpUser> connected = List.copyOf(users.getUsers());
                log.debug("Pushing authority change to {} connected account(s)", connected.size());
                connected.forEach(user -> send(user.getName()));
            }
        }
    }

    /**
     * Sends one account its current grants.
     *
     * <p>Costs nothing when they have no session here: the broker drops a
     * message for a user it does not know, so there is no need to check first.
     */
    private void send(String name) {
        long userId;
        try {
            userId = Long.parseLong(name);
        } catch (NumberFormatException e) {
            // The principal's name is always the account id — see
            // StompAuthChannelInterceptor. Anything else is not one of ours.
            return;
        }

        authorizationService.entryOf(userId)
                .filter(AuthorityCache.Entry::canSignIn)
                .ifPresent(entry -> messaging.convertAndSendToUser(
                        name,
                        DESTINATION,
                        AuthoritiesResponse.from(entry, rowLevel.scopedResourcesFor(userId))));
    }
}
