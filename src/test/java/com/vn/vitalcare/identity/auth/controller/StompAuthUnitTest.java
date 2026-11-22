package com.vn.vitalcare.identity.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.auth.service.AuthorizationService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Report-5.1 BE_Infra: StompAuthChannelInterceptor (the whole security boundary of /ws) and AuthVersionAdvice. */
class StompAuthUnitTest {

    private static final Instant ISSUED = Instant.parse("2026-11-22T02:00:00Z");

    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(decoder, authorization);
    private final MessageChannel channel = mock(MessageChannel.class);

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(subject).issuedAt(ISSUED).build();
    }

    private static List<GrantedAuthority> grants(String... codes) {
        return java.util.Arrays.stream(codes).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    private static StompHeaderAccessor frame(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private Message<byte[]> send(StompHeaderAccessor accessor) {
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        interceptor.preSend(message, channel);
        return message;
    }

    private static StompHeaderAccessor subscribe(String destination, Principal user) {
        StompHeaderAccessor a = frame(StompCommand.SUBSCRIBE);
        a.setDestination(destination);
        a.setUser(user);
        return a;
    }

    private static Principal signedIn(String id) {
        return new JwtAuthenticationToken(jwt(id), List.of(), id);
    }

    @Nested
    @DisplayName("CONNECT")
    class Connect {

        @Test
        @DisplayName("A valid bearer token in the CONNECT frame makes the user id the session's principal")
        void st01_validTokenSetsPrincipal() {
            when(decoder.decode("abc")).thenReturn(jwt("7"));
            when(authorization.authoritiesOf("7", ISSUED)).thenReturn(grants("alerts:read"));
            StompHeaderAccessor a = frame(StompCommand.CONNECT);
            a.setNativeHeader("Authorization", "Bearer abc");
            send(a);
            assertThat(a.getUser()).isNotNull();
            assertThat(a.getUser().getName()).isEqualTo("7");
        }

        @Test
        @DisplayName("No Authorization header, or one that is not Bearer: Not signed in, and nothing is decoded")
        void st02_missingHeaderRefused() {
            assertThatThrownBy(() -> send(frame(StompCommand.CONNECT)))
                    .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("Not signed in");
            StompHeaderAccessor basic = frame(StompCommand.CONNECT);
            basic.setNativeHeader("Authorization", "Basic abc");
            assertThatThrownBy(() -> send(basic)).isInstanceOf(MessageDeliveryException.class);
            verify(decoder, never()).decode(anyString());
        }

        @Test
        @DisplayName("An expired or forged token answers only Not signed in, without saying which")
        void st03_badTokenRefused() {
            when(decoder.decode("bad")).thenThrow(new BadJwtException("expired"));
            StompHeaderAccessor a = frame(StompCommand.CONNECT);
            a.setNativeHeader("Authorization", "Bearer bad");
            assertThatThrownBy(() -> send(a)).isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("Not signed in").hasMessageNotContaining("expired");
        }

        @Test
        @DisplayName("A token of an account disabled or signed out elsewhere: This account is no longer active")
        void st04_inactiveAccountRefused() {
            when(decoder.decode("abc")).thenReturn(jwt("7"));
            when(authorization.authoritiesOf("7", ISSUED)).thenThrow(new IllegalStateException("revoked"));
            StompHeaderAccessor a = frame(StompCommand.CONNECT);
            a.setNativeHeader("Authorization", "Bearer abc");
            assertThatThrownBy(() -> send(a)).isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("This account is no longer active");
        }
    }

    @Nested
    @DisplayName("SUBSCRIBE and SEND")
    class Subscribe {

        @Test
        @DisplayName("A subscription needs <segment>:read held now, not at CONNECT")
        void st05_permissionCheckedNow() {
            when(authorization.authoritiesOf("7")).thenReturn(grants("notifications:read"));
            assertThatCode(() -> send(subscribe("/user/queue/notifications", signedIn("7")))).doesNotThrowAnyException();
            when(authorization.authoritiesOf("7")).thenReturn(grants());
            assertThatThrownBy(() -> send(subscribe("/user/queue/notifications", signedIn("7"))))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("You do not have the notifications:read permission");
        }

        @Test
        @DisplayName("/user/queue/auth needs only being signed in; no permission is looked up")
        void st06_authQueueSelf() {
            assertThatCode(() -> send(subscribe("/user/queue/auth", signedIn("7")))).doesNotThrowAnyException();
            verify(authorization, never()).authoritiesOf(anyString());
        }

        @Test
        @DisplayName("Subscribing without a CONNECT principal: Not signed in")
        void st07_noPrincipalRefused() {
            assertThatThrownBy(() -> send(subscribe("/topic/exam_rooms/x", null)))
                    .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("Not signed in");
        }

        @Test
        @DisplayName("A destination with no resource segment, or none at all: Unknown destination")
        void st08_unknownDestination() {
            assertThatThrownBy(() -> send(subscribe("/topic", signedIn("7"))))
                    .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("Unknown destination");
            assertThatThrownBy(() -> send(subscribe(null, signedIn("7"))))
                    .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("Unknown destination");
        }

        @Test
        @DisplayName("An account disabled while its socket is open cannot subscribe")
        void st09_disabledWhileOpen() {
            when(authorization.authoritiesOf("7")).thenThrow(new IllegalStateException("disabled"));
            assertThatThrownBy(() -> send(subscribe("/topic/exam_rooms/x", signedIn("7"))))
                    .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("This account is no longer active");
        }

        @Test
        @DisplayName("SEND is refused: the server accepts no client messages; heartbeats and DISCONNECT pass")
        void st10_sendRefusedOthersPass() {
            assertThatThrownBy(() -> send(frame(StompCommand.SEND)))
                    .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("does not accept messages");
            assertThatCode(() -> send(frame(StompCommand.DISCONNECT))).doesNotThrowAnyException();
            Message<byte[]> heartbeat = MessageBuilder.withPayload(new byte[0]).build();
            assertThat(interceptor.preSend(heartbeat, channel)).isSameAs(heartbeat);
        }

        @Test
        @DisplayName("permissionFor: /user is stripped, the second segment names the permission")
        void st11_permissionFor() {
            assertThat(StompAuthChannelInterceptor.permissionFor("/topic/exam_rooms/abc")).isEqualTo("exam_rooms:read");
            assertThat(StompAuthChannelInterceptor.permissionFor("/user/queue/my_appointments")).isEqualTo("my_appointments:read");
            assertThat(StompAuthChannelInterceptor.permissionFor("/user/queue/auth")).isEqualTo("auth");
            assertThat(StompAuthChannelInterceptor.permissionFor("/queue/")).isNull();
            assertThat(StompAuthChannelInterceptor.permissionFor(null)).isNull();
        }
    }

    @Nested
    @DisplayName("AuthVersionAdvice")
    class Version {

        private final AuthVersionAdvice advice = new AuthVersionAdvice(authorization);

        @AfterEach
        void clear() {
            SecurityContextHolder.clearContext();
        }

        private HttpHeaders answer(Object body, Object[] returned) {
            MockHttpServletResponse raw = new MockHttpServletResponse();
            ServletServerHttpResponse response = new ServletServerHttpResponse(raw);
            returned[0] = advice.beforeBodyWrite(body, null, null, null,
                    new ServletServerHttpRequest(new MockHttpServletRequest()), response);
            return response.getHeaders();
        }

        @Test
        @DisplayName("A signed-in answer carries X-Auth-Version of the caller; the body is returned untouched")
        void av01_stampsSignedIn() {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("7")));
            when(authorization.versionOf(7L)).thenReturn("v42");
            Object[] returned = new Object[1];
            Object body = List.of("x");
            assertThat(answer(body, returned).getFirst(AuthVersionAdvice.HEADER)).isEqualTo("v42");
            assertThat(returned[0]).isSameAs(body);
            assertThat(advice.supports(null, null)).isTrue();
        }

        @Test
        @DisplayName("An open endpoint (nobody signed in) gets no header")
        void av02_noHeaderWhenAnonymous() {
            Object[] returned = new Object[1];
            assertThat(answer("body", returned).getFirst(AuthVersionAdvice.HEADER)).isNull();
            verify(authorization, never()).versionOf(anyLong());
        }
    }
}
