package com.vn.vitalcare.share.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.auth.service.AuthorityChangeChannel;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPoliciesChanged;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelPolicyChannel;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** Report-5.1 BE_Infra2: PostgresEventBus and the two channels that ride on it (authority and row-level policy changes). */
class EventBusUnitTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PostgresEventBus bus = new PostgresEventBus(jdbc, events, "jdbc:postgresql://nowhere/db", "u", "p");

    private String nodeId() {
        return (String) ReflectionTestUtils.getField(bus, "nodeId");
    }

    private void receive(String message) {
        ReflectionTestUtils.invokeMethod(bus, "receive", message);
    }

    @Nested
    @DisplayName("PostgresEventBus")
    class Bus {

        @Test
        @DisplayName("publish sends pg_notify on the vitalcare_events channel as node:kind:payload")
        void eb01_publishFormatsTheMessage() {
            bus.publish("auth", "user:7");
            ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
            verify(jdbc).queryForObject(eq("select pg_notify(?, ?)"), eq(String.class), args.capture(), args.capture());
            assertThat(args.getAllValues().get(0)).isEqualTo("vitalcare_events");
            assertThat(args.getAllValues().get(1)).isEqualTo(nodeId() + ":auth:user:7");
        }

        @Test
        @DisplayName("A database error while publishing is logged, never thrown into the change that caused it")
        void eb02_publishFailureSwallowed() {
            when(jdbc.queryForObject(anyString(), eq(String.class), any(), any()))
                    .thenThrow(new DataAccessResourceFailureException("down"));
            assertThatCode(() -> bus.publish("auth", "all")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("A message from another node becomes a RemoteEvent with its kind and payload (payload may hold colons)")
        void eb03_remoteMessagePublished() {
            receive("othernode:auth:user:7");
            verify(events).publishEvent(new PostgresEventBus.RemoteEvent("auth", "user:7"));
        }

        @Test
        @DisplayName("This node's own message, or a malformed one, is ignored")
        void eb04_ownOrMalformedIgnored() {
            receive(nodeId() + ":auth:all");
            receive("only:two");
            receive("garbage");
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("Boundary: before start the bus is not running; stop without start does not fail")
        void eb05_lifecycleWithoutStart() {
            assertThat(bus.isRunning()).isFalse();
            assertThatCode(bus::stop).doesNotThrowAnyException();
            assertThat(bus.isRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("AuthorityChangeChannel")
    class Authority {

        private final PostgresEventBus mockBus = mock(PostgresEventBus.class);
        private final AuthorityChangeChannel channel = new AuthorityChangeChannel(mockBus, events);

        @Test
        @DisplayName("A change on this node is told to the others: user:<id> for one account, all for a role")
        void ac01_broadcast() {
            channel.broadcast(new AuthoritiesChanged.ForUser(7L));
            channel.broadcast(new AuthoritiesChanged.ForEveryone());
            verify(mockBus).publish("auth", "user:7");
            verify(mockBus).publish("auth", "all");
        }

        @Test
        @DisplayName("A remote auth message is turned back into the same change and published locally")
        void ac02_remoteParsed() {
            channel.onRemote(new PostgresEventBus.RemoteEvent("auth", "user:7"));
            channel.onRemote(new PostgresEventBus.RemoteEvent("auth", "all"));
            verify(events).publishEvent(new AuthorityChangeChannel.Received(new AuthoritiesChanged.ForUser(7L)));
            verify(events).publishEvent(new AuthorityChangeChannel.Received(new AuthoritiesChanged.ForEveryone()));
        }

        @Test
        @DisplayName("Another kind, or an unreadable auth message (no id, non-numeric id, unknown word), is ignored")
        void ac03_unrecognisedIgnored() {
            channel.onRemote(new PostgresEventBus.RemoteEvent("rls", "changed"));
            channel.onRemote(new PostgresEventBus.RemoteEvent("auth", "user"));
            channel.onRemote(new PostgresEventBus.RemoteEvent("auth", "user:abc"));
            channel.onRemote(new PostgresEventBus.RemoteEvent("auth", "someone"));
            verify(events, never()).publishEvent(any(Object.class));
        }
    }

    @Nested
    @DisplayName("RowLevelPolicyChannel")
    class Policies {

        private final PostgresEventBus mockBus = mock(PostgresEventBus.class);
        private final RowLevelPolicyChannel channel = new RowLevelPolicyChannel(mockBus, events);

        @Test
        @DisplayName("A policy change is told to the other nodes as rls:changed")
        void rl01_broadcast() {
            channel.broadcast(new RowLevelPoliciesChanged.Changed());
            verify(mockBus).publish("rls", "changed");
        }

        @Test
        @DisplayName("A remote rls message is published locally; other kinds are not")
        void rl02_remote() {
            channel.onRemote(new PostgresEventBus.RemoteEvent("auth", "all"));
            verify(events, never()).publishEvent(any(Object.class));
            channel.onRemote(new PostgresEventBus.RemoteEvent("rls", "changed"));
            verify(events).publishEvent(new RowLevelPolicyChannel.Received());
        }
    }
}
