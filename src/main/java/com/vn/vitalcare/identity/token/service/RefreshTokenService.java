package com.vn.vitalcare.identity.token.service;

import com.vn.vitalcare.identity.token.entity.RefreshToken;
import com.vn.vitalcare.identity.token.repository.RefreshTokenRepository;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.share.security.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>Its own domain rather than a part of {@code identity.auth}, because the
 * user domain needs it too: disabling an account or resetting its password
 * has to end the sessions already open under it. Keeping it separate is what
 * lets both {@code AuthService} and {@code UserService} depend on it without
 * the two of them depending on each other.
 *
 * <p>The token is 32 random bytes, not a JWT. There is nothing to read inside
 * it — its only job is to be unguessable and to name a row in this table — so
 * signing and claims would be weight without purpose.
 */
@Service
@Transactional(readOnly = true)
public class RefreshTokenService {

    /** 256 bits of entropy: not guessable, and short enough to sit in a header. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final JwtProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, JwtProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Mints a token for {@code user} and returns it in the clear.
     *
     * <p>This is the only moment the raw value exists on the server. What is
     * stored is its SHA-256, so a dump of the table cannot be replayed against
     * the refresh endpoint.
     */
    @Transactional
    public String issue(User user, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        repository.save(new RefreshToken(user, hash(raw), now.plus(properties.refreshTokenTtl())));
        return raw;
    }

    /**
     * Exchanges a presented token for the user it belongs to, and burns it.
     *
     * <p>Rotation on every use is what makes a stolen refresh token detectable
     * in principle and short-lived in practice: the thief and the legitimate
     * client cannot both keep using it, because the first one to refresh
     * invalidates the copy the other holds.
     *
     * @throws BadCredentialsException if the token is unknown, already used,
     *                                 revoked or expired — all four are the
     *                                 same answer to whoever presented it.
     */
    @Transactional
    public User consume(String rawToken, Instant now) {
        RefreshToken token = repository.findByTokenHash(hash(rawToken))
                .filter(candidate -> candidate.isUsable(now))
                .orElseThrow(() -> new BadCredentialsException("Refresh token is invalid or has expired"));

        token.revoke(now);
        repository.save(token);
        return token.getUser();
    }

    /** Ends one session. Unknown tokens are ignored: signing out cannot fail. */
    @Transactional
    public void revoke(String rawToken, Instant now) {
        Optional<RefreshToken> token = repository.findByTokenHash(hash(rawToken));
        token.ifPresent(found -> {
            found.revoke(now);
            repository.save(found);
        });
    }

    /**
     * Ends every session open under an account.
     *
     * <p>Called when an account is disabled or its password is reset. Access
     * tokens already issued keep working until they expire — that is the
     * trade-off stateless authorisation makes — so the window this closes is
     * bounded by {@code app.security.jwt.access-token-ttl}, not by zero.
     */
    @Transactional
    public void revokeAllFor(User user, Instant now) {
        List<RefreshToken> open = repository.findByUserAndRevokedAtIsNull(user);
        open.forEach(token -> token.revoke(now));
        repository.saveAll(open);
    }

    /** Housekeeping: removes rows that can no longer affect any decision. */
    @Transactional
    public int purgeSpent(Instant now) {
        return repository.deleteSpent(now);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; if it is missing the
            // JVM is broken in a way no fallback here could paper over.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
