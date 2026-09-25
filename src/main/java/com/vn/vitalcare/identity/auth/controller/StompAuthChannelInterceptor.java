package com.vn.vitalcare.identity.auth.controller;

import com.vn.vitalcare.identity.auth.service.AuthorizationService;
import java.util.Collection;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authentication and authorisation for the STOMP channel.
 *
 * <p>The servlet filter chain runs once, at the HTTP handshake, and then has
 * nothing more to say: the frames that follow are not requests and never reach
 * it. So this is the entire security boundary for everything a client sends
 * over {@code /ws}.
 *
 * <h2>CONNECT: where the token can finally be sent properly</h2>
 *
 * <p>The browser's {@code WebSocket} constructor cannot set an
 * {@code Authorization} header. STOMP sidesteps it: the token travels in the
 * {@code CONNECT} frame, which is ordinary message content rather than an HTTP
 * header, so it never lands in an access log, a browser history or a
 * {@code Referer} the way a token in the query string would.
 *
 * <h2>The trap this class exists to avoid</h2>
 *
 * <p>An access token lives fifteen minutes; a WebSocket lives for hours.
 * Authenticating once at {@code CONNECT} and trusting the resulting
 * {@code Principal} for the life of the connection would reintroduce exactly
 * the staleness the token redesign removed. So the {@code Principal}
 * established here is treated as an identity claim and nothing more. Every
 * authorisation decision afterwards asks {@code AuthorityCache} again — a
 * memory read, which is affordable at message rates in a way a database read
 * would not be.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    /** Same scheme and header name as the REST side, for one habit rather than two. */
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";

    /**
     * Spring's per-session prefix. A client subscribes to
     * {@code /user/queue/...} and the broker resolves it to that client's own
     * session, so one connection cannot reach another's messages by guessing a
     * destination.
     */
    private static final String USER_PREFIX = "/user";

    private final JwtDecoder jwtDecoder;
    private final AuthorizationService authorizationService;

    public StompAuthChannelInterceptor(JwtDecoder jwtDecoder, AuthorizationService authorizationService) {
        this.jwtDecoder = jwtDecoder;
        this.authorizationService = authorizationService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            // A heartbeat, or a frame the protocol handler produced itself.
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case SEND -> throw new MessageDeliveryException(
                    // Nothing on the server accepts a client message yet, so
                    // there is no destination this could legitimately be for.
                    "This connection does not accept messages");
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK, NACK — nothing to decide.
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            throw new MessageDeliveryException("Not signed in");
        }

        Jwt token;
        try {
            token = jwtDecoder.decode(header.substring(BEARER.length()));
        } catch (JwtException e) {
            // Expired, tampered with, or signed by something else. The client
            // refreshes over HTTP and reconnects; saying which it was would
            // only help somebody probing.
            throw new MessageDeliveryException("Not signed in");
        }

        Collection<GrantedAuthority> authorities = resolve(token.getSubject());

        // The principal's name is the user id, because that is what
        // `convertAndSendToUser` matches a message against.
        accessor.setUser(new JwtAuthenticationToken(token, authorities, token.getSubject()));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new MessageDeliveryException("Not signed in");
        }

        String destination = accessor.getDestination();
        String required = permissionFor(destination);

        if (SELF.equals(required)) {
            // Being signed in is the whole requirement, which is the same rule
            // /api/auth/* follows on the HTTP side.
            return;
        }
        if (required == null) {
            throw new MessageDeliveryException("Unknown destination");
        }

        // Resolved now, not reused from CONNECT. A subscription opened three
        // hours into a session is authorised against what the account may do
        // now.
        boolean allowed = resolve(accessor.getUser().getName()).stream()
                .anyMatch(granted -> required.equals(granted.getAuthority()));

        if (!allowed) {
            throw new MessageDeliveryException("You do not have the %s permission".formatted(required));
        }
    }

    /**
     * Marks the destinations that need no permission beyond being signed in.
     *
     * <p>Not a real code, and never compared against a granted authority — see
     * the check in {@link #authorizeSubscription}.
     */
    private static final String SELF = "auth";

    /**
     * The permission code a destination needs, derived rather than looked up.
     *
     * <p>One permission vocabulary across HTTP and WebSocket: a table mapping
     * destinations to codes would be a second copy of a rule that already
     * exists, free to drift from it.
     *
     * <p>A subscription only ever reads, so the action is always {@code read}.
     * The one exception is {@code /user/queue/auth}, which carries the
     * account's own grants and mirrors {@code /api/auth/*}: signed in is
     * enough.
     */
    static String permissionFor(String destination) {
        if (destination == null) {
            return null;
        }

        String path = destination.startsWith(USER_PREFIX)
                ? destination.substring(USER_PREFIX.length())
                : destination;

        // "/queue/notifications" -> ["", "queue", "notifications"]
        String[] segments = path.split("/");
        if (segments.length < 3 || segments[2].isBlank()) {
            return null;
        }
        return SELF.equals(segments[2]) ? SELF : segments[2] + ":read";
    }

    private Collection<GrantedAuthority> resolve(String subject) {
        try {
            return authorizationService.authoritiesOf(subject);
        } catch (RuntimeException e) {
            // The account was disabled or deleted while the socket was open.
            throw new MessageDeliveryException("This account is no longer active");
        }
    }
}
