package com.vn.vitalcare.share.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Token settings, bound from {@code app.security.jwt.*}.
 *
 * @param secret        HMAC signing key. HS256 needs at least 32 bytes; the
 *                      application refuses to start with a shorter one rather
 *                      than signing tokens nothing should trust. Override it
 *                      per environment with {@code APP_SECURITY_JWT_SECRET}.
 * @param issuer        the {@code iss} claim, and what the decoder requires.
 * @param accessTokenTtl how long an access token stays valid. Short, because
 *                      nothing can revoke one before it expires — see
 *                      {@link com.dth.frw.domain.security.token.RefreshToken}.
 * @param refreshTokenTtl how long a refresh token stays valid, i.e. how long a
 *                      session survives without signing in again.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {

    /** Shortest key HS256 may be signed with, in bytes. */
    public static final int MIN_SECRET_LENGTH = 32;
}
