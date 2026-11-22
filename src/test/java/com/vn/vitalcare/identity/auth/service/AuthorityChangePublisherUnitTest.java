package com.vn.vitalcare.identity.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.auth.dto.AuthoritiesResponse;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

/** Report-5.1 BE_Infra: AuthorityChangePublisher, pushing an open tab its new grants on /user/queue/auth. */
class AuthorityChangePublisherUnitTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final SimpUserRegistry users = mock(SimpUserRegistry.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final RowLevelSecurity rowLevel = mock(RowLevelSecurity.class);
    private final AuthorityChangePublisher publisher = new AuthorityChangePublisher(messaging, users, authorization, rowLevel);

    private static AuthorityCache.Entry entry(boolean canSignIn) {
        return new AuthorityCache.Entry(canSignIn, List.of(), List.of("DOCTOR"), List.of("alerts:read"), Set.of(1L),
                null, null, null, "v2", null, 0L);
    }

    private static SimpUser user(String name) {
        SimpUser u = mock(SimpUser.class);
        when(u.getName()).thenReturn(name);
        return u;
    }

    @Test
    @DisplayName("One account's change pushes that account its roles, permissions, version and scoped resources")
    void ac01_forUserPushesGrants() {
        when(authorization.entryOf(7L)).thenReturn(Optional.of(entry(true)));
        when(rowLevel.scopedResourcesFor(7L)).thenReturn(List.of("customers"));
        publisher.onLocalChange(new AuthoritiesChanged.ForUser(7L));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSendToUser(eq("7"), eq(AuthorityChangePublisher.DESTINATION), payload.capture());
        assertThat(payload.getValue()).isEqualTo(new AuthoritiesResponse(List.of("DOCTOR"), List.of("alerts:read"), "v2", List.of("customers")));
    }

    @Test
    @DisplayName("A role edited: every account with a socket on this node is told; a non-numeric principal is skipped")
    void ac02_forEveryoneConnected() {
        SimpUser a = user("7");
        SimpUser b = user("8");
        SimpUser odd = user("guest-session");
        when(users.getUsers()).thenReturn(Set.of(a, b, odd));
        when(authorization.entryOf(7L)).thenReturn(Optional.of(entry(true)));
        when(authorization.entryOf(8L)).thenReturn(Optional.of(entry(true)));
        publisher.onLocalChange(new AuthoritiesChanged.ForEveryone());
        verify(messaging).convertAndSendToUser(eq("7"), anyString(), any());
        verify(messaging).convertAndSendToUser(eq("8"), anyString(), any());
        verify(messaging, never()).convertAndSendToUser(eq("guest-session"), anyString(), any());
    }

    @Test
    @DisplayName("A disabled or deleted account is not sent grants")
    void ac03_cannotSignInGetsNothing() {
        when(authorization.entryOf(7L)).thenReturn(Optional.of(entry(false)));
        when(authorization.entryOf(9L)).thenReturn(Optional.empty());
        publisher.onLocalChange(new AuthoritiesChanged.ForUser(7L));
        publisher.onLocalChange(new AuthoritiesChanged.ForUser(9L));
        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("A change that came from another node is pushed to this node's sessions the same way")
    void ac04_remoteChange() {
        when(authorization.entryOf(7L)).thenReturn(Optional.of(entry(true)));
        when(rowLevel.scopedResourcesFor(7L)).thenReturn(List.of());
        publisher.onRemoteChange(new AuthorityChangeChannel.Received(new AuthoritiesChanged.ForUser(7L)));
        verify(messaging).convertAndSendToUser(eq("7"), eq(AuthorityChangePublisher.DESTINATION), any());
    }
}
