package com.vn.vitalcare.identity.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vn.vitalcare.identity.role.service.RoleService;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.entity.UserStatus;
import com.vn.vitalcare.identity.user.repository.UserRepository;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.security.AuthoritiesChanged;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Changing a password ends the other sessions at once, not when their access
 * token expires (KAN-80): the account is stamped, and a token issued before the
 * stamp is refused while the pair handed back to the changer still counts.
 */
class SessionRevocationTest {

    private static final Instant CHANGED = Instant.parse("2026-11-19T08:00:00Z");

    @Test
    @DisplayName("changing the password stamps the account, revokes its refresh tokens and drops its cache entry")
    void changeOwnPasswordEndsSessions() {
        UserRepository repository = mock(UserRepository.class);
        RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        UserService service = new UserService(repository, mock(RoleService.class), refreshTokens, encoder, events);

        User user = new User("+84911000007", null, "old-hash", "Bệnh nhân Test", UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", 7L);
        when(repository.findById(7L)).thenReturn(Optional.of(user));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(encoder.matches("Current#2026", "old-hash")).thenReturn(true);
        when(encoder.encode(anyString())).thenReturn("new-hash");

        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        service.changeOwnPassword(7L, "Current#2026", "VcTest#2026a");

        Instant stamp = user.getSessionsRevokedAt();
        assertEquals(stamp.truncatedTo(ChronoUnit.SECONDS), stamp, "stamped to the second, as a token's iat is");
        assertFalse(stamp.isBefore(before));
        verify(refreshTokens).revokeAllFor(eq(user), any());
        verify(events).publishEvent(new AuthoritiesChanged.ForUser(7L));
    }

    @Test
    @DisplayName("a token issued before the stamp is refused; one issued at or after it is honoured")
    void tokenIssuedBeforeTheStampIsRefused() {
        AuthorizationService authorization = serviceWith(CHANGED);

        assertThrows(InvalidBearerTokenException.class,
                () -> authorization.authoritiesOf("7", CHANGED.minusSeconds(1)));
        assertThrows(InvalidBearerTokenException.class,
                () -> authorization.authoritiesOf("7", null));
        // The pair handed back by the change itself: issued in the same second.
        assertDoesNotThrow(() -> authorization.authoritiesOf("7", CHANGED));
        assertDoesNotThrow(() -> authorization.authoritiesOf("7", CHANGED.plusSeconds(60)));
    }

    @Test
    @DisplayName("an account whose sessions were never ended is not affected")
    void noStampNoRefusal() {
        AuthorizationService authorization = serviceWith(null);

        assertDoesNotThrow(() -> authorization.authoritiesOf("7", CHANGED.minusSeconds(3600)));
    }

    private static AuthorizationService serviceWith(Instant sessionsRevokedAt) {
        AuthorityCache cache = mock(AuthorityCache.class);
        AuthorityCache.Entry entry = new AuthorityCache.Entry(
                true, List.of(), List.of(), List.of(), Set.of(), null, null, null, "v",
                sessionsRevokedAt, System.nanoTime());
        when(cache.get(7L)).thenReturn(Optional.of(entry));
        return new AuthorizationService(cache);
    }
}
