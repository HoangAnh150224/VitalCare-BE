package com.vn.vitalcare.share.security;

import com.vn.vitalcare.identity.user.entity.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Mints the access tokens the API authorises requests with.
 *
 * <p>The token identifies the caller and nothing more: a subject, a display
 * name and a lifetime. It deliberately carries <em>no</em> roles and no
 * permission codes.
 *
 * <p>Stamping them in used to make authorising a request a pure signature
 * check, but it also made the token grow with the permission model — an
 * application with a few hundred codes ends up sending kilobytes of header on
 * every request, and every request is where it hurts. Authorities are therefore
 * resolved per request from the database instead; see
 * {@code AuthorizationService}.
 *
 * <p>Two things follow from that, both improvements. A permission taken away
 * takes effect on the next request rather than at the next token expiry, and a
 * disabled account stops being able to use the token it already holds. What it
 * costs is one indexed read per request, which is the price of not being stale.
 */
@Service
public class JwtService {

    /**
     * Claim holding the account's display name, for messages and audit text.
     *
     * <p>The full name rather than the sign-in identifier: that identifier is
     * now a phone number, and an audit trail reading "+84900000001 changed this
     * policy" is both less useful than a name and a piece of personal data with
     * no reason to be in a token or a log line.
     */
    public static final String CLAIM_NAME = "name";

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public JwtService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * An access token for {@code user}, valid from now for the configured TTL.
     *
     * <p>{@code sub} is the user id as a string: it is what
     * {@link CurrentUser#id()} reads back and what the authorities are resolved
     * from, so it has to stay parseable as a {@code long}.
     */
    public String issueAccessToken(User user, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(properties.accessTokenTtl()))
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_NAME, user.getFullName())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Seconds until an access token issued now expires — the {@code expiresIn} the client is told. */
    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    public Instant refreshTokenExpiry(Instant issuedAt) {
        return issuedAt.plus(properties.refreshTokenTtl());
    }
}
