package com.vn.vitalcare.identity.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.vn.vitalcare.identity.auth.dto.TokenResponse;
import com.vn.vitalcare.identity.rowlevel.service.RowLevelSecurity;
import com.vn.vitalcare.identity.token.entity.RefreshToken;
import com.vn.vitalcare.identity.token.repository.RefreshTokenRepository;
import com.vn.vitalcare.identity.token.service.RefreshTokenService;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import com.vn.vitalcare.share.security.JwtProperties;
import com.vn.vitalcare.share.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** UC Log In (session part), sheet BE_Auth UTC013–UTC021: token issuing, refresh and logout. */
class GuestSessionUnitTest {

    private static final JwtProperties PROPS = new JwtProperties(
            "unit-test-secret-unit-test-secret-1234", "vitalcare", Duration.ofMinutes(15), Duration.ofDays(30));

    @Nested
    @DisplayName("JwtService.issueAccessToken")
    class Jwt_ {

        private final SecretKey key = new SecretKeySpec(PROPS.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        private final JwtService jwt = new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), PROPS);

        @Test void utc13_tokenCarriesIdentityOnly() {
            User user = mock(User.class);
            when(user.getId()).thenReturn(42L);
            when(user.getFullName()).thenReturn("Nguyen Van A");
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

            String token = jwt.issueAccessToken(user, now);
            Jwt decoded = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().decode(token);

            assertEquals("42", decoded.getSubject());
            assertEquals("Nguyen Van A", decoded.getClaimAsString("name"));
            assertEquals("vitalcare", decoded.getClaimAsString("iss"));
            assertEquals(now.plus(Duration.ofMinutes(15)), decoded.getExpiresAt());
            // Roles are not in the token; /api/auth/permissions answers them.
            assertFalse(decoded.hasClaim("roles"));
            assertFalse(decoded.hasClaim("scope"));
        }

        @Test void utc14_ttlInSeconds() {
            assertEquals(900L, jwt.accessTokenTtlSeconds());
        }
    }

    @Nested
    @DisplayName("RefreshTokenService")
    class Refresh {

        private RefreshTokenRepository repo;
        private RefreshTokenService service;
        private final User user = mock(User.class);
        private final Instant now = Instant.parse("2026-11-01T00:00:00Z");

        @BeforeEach
        void setUp() {
            repo = mock(RefreshTokenRepository.class);
            service = new RefreshTokenService(repo, PROPS);
        }

        @Test void utc15_issueStoresOnlyTheHash() {
            String raw = service.issue(user, now);
            ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
            verify(repo).save(saved.capture());
            assertNotNull(raw);
            assertNotEquals(raw, saved.getValue().getTokenHash());
            assertEquals(64, saved.getValue().getTokenHash().length());
            assertEquals(now.plus(Duration.ofDays(30)), saved.getValue().getExpiresAt());
        }

        @Test void utc16_consumeValidTokenRevokesIt() {
            RefreshToken token = new RefreshToken(user, "h", now.plusSeconds(60));
            when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            assertEquals(user, service.consume("raw", now));
            assertEquals(now, token.getRevokedAt());
        }

        @Test void utc17_consumeExpiredOrUsedTokenRefused() {
            RefreshToken expired = new RefreshToken(user, "h", now.minusSeconds(1));
            when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(expired));
            BadCredentialsException e = assertThrows(BadCredentialsException.class, () -> service.consume("raw", now));
            assertEquals("Refresh token is invalid or has expired", e.getMessage());

            RefreshToken used = new RefreshToken(user, "h", now.plusSeconds(60));
            used.revoke(now.minusSeconds(5));
            when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(used));
            assertThrows(BadCredentialsException.class, () -> service.consume("raw", now));
        }

        @Test void utc18_consumeUnknownTokenRefused() {
            when(repo.findByTokenHash(anyString())).thenReturn(Optional.empty());
            assertThrows(BadCredentialsException.class, () -> service.consume("garbage", now));
        }
    }

    @Nested
    @DisplayName("AuthService.refresh / logout")
    class Session {

        private RefreshTokenService refresh;
        private AuthService auth;
        private User user;

        @BeforeEach
        void setUp() {
            refresh = mock(RefreshTokenService.class);
            JwtService jwt = mock(JwtService.class);
            when(jwt.issueAccessToken(any(), any())).thenReturn("access.jwt");
            when(jwt.accessTokenTtlSeconds()).thenReturn(900L);
            when(refresh.issue(any(), any())).thenReturn("refresh-new");
            auth = new AuthService(mock(UserService.class), refresh, jwt, mock(PasswordEncoder.class),
                    mock(AuthorizationService.class), mock(RowLevelSecurity.class));
            user = mock(User.class);
            when(user.getFullName()).thenReturn("Nguyen Van A");
        }

        @Test void utc19_refreshActiveAccountGivesNewPair() {
            when(user.canSignIn()).thenReturn(true);
            when(refresh.consume(any(), any())).thenReturn(user);
            TokenResponse tokens = auth.refresh("refresh-old");
            assertEquals("access.jwt", tokens.accessToken());
            assertEquals("refresh-new", tokens.refreshToken());
        }

        @Test void utc20_refreshDisabledAccountRefused() {
            when(user.canSignIn()).thenReturn(false);
            when(refresh.consume(any(), any())).thenReturn(user);
            BadCredentialsException e = assertThrows(BadCredentialsException.class, () -> auth.refresh("refresh-old"));
            assertEquals("This account is no longer active", e.getMessage());
            verify(refresh, never()).issue(any(), any());
        }

        @Test void utc21_logoutNeverFails() {
            auth.logout(null);
            auth.logout("  ");
            verify(refresh, never()).revoke(any(), any());
            auth.logout("refresh-raw");
            verify(refresh).revoke(org.mockito.ArgumentMatchers.eq("refresh-raw"), any());
            assertTrue(true);
        }
    }
}
