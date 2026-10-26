package com.vn.vitalcare.care.registration.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Checks the ID token Firebase Phone Auth hands the browser once the person
 * has typed the code Google texted them, and reads the number it proves.
 *
 * <p>Firebase sends the SMS and checks the code; all this side trusts is the
 * token: signed by Google's {@code securetoken} keys, issued for this project,
 * unexpired, and from a sign-in in the last few minutes (an old token is not
 * proof that the person holds the phone now). No Firebase SDK and no service
 * account: the token is an ordinary JWT, checked with the decoder Spring
 * Security already ships.
 *
 * <p>Active when {@code APP_FIREBASE_PROJECT_ID} is set; then codes go out
 * through Firebase instead of {@link OtpSender}.
 */
@Component
@ConditionalOnExpression("!'${app.otp.firebase-project-id:}'.isBlank()")
public class FirebasePhoneTokens {

    private static final String GOOGLE_KEYS =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    /** How long after signing in with the code the token still proves it. */
    static final Duration FRESH = Duration.ofMinutes(10);

    private final JwtDecoder decoder;

    @Autowired
    public FirebasePhoneTokens(@Value("${app.otp.firebase-project-id}") String projectId, Clock clock) {
        this(NimbusJwtDecoder.withJwkSetUri(GOOGLE_KEYS).restOperations(withTimeouts()).build(),
                projectId.trim(), clock);
    }

    /** With any decoder, so a test can sign its own tokens; the checks are the same. */
    FirebasePhoneTokens(JwtDecoder decoder, String projectId, Clock clock) {
        if (decoder instanceof NimbusJwtDecoder nimbus) {
            nimbus.setJwtValidator(validator(projectId, clock));
        }
        this.decoder = decoder;
    }

    /**
     * The number a token proves, in E.164 ({@code +84912345678}).
     *
     * @throws InvalidVerificationCodeException a token that is forged, expired,
     *         for another project, stale, or carries no number — the person
     *         sees the same "wrong code" answer as with a code of ours
     */
    public String verifiedPhone(String token) {
        try {
            return Optional.ofNullable(decoder.decode(token).getClaimAsString("phone_number"))
                    .filter(phone -> !phone.isBlank())
                    .orElseThrow(InvalidVerificationCodeException::new);
        } catch (JwtException e) {
            throw new InvalidVerificationCodeException();
        }
    }

    /** Google's keys are fetched (and cached) on a request thread: it must not hang there. */
    private static org.springframework.web.client.RestTemplate withTimeouts() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new org.springframework.web.client.RestTemplate(factory);
    }

    static OAuth2TokenValidator<Jwt> validator(String projectId, Clock clock) {
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<>(
                "aud", (Object aud) -> aud instanceof java.util.Collection<?> list && list.contains(projectId));
        OAuth2TokenValidator<Jwt> fresh = new JwtClaimValidator<>("auth_time", (Object value) -> {
            Instant signedIn = value instanceof Instant instant ? instant
                    : value instanceof Number seconds ? Instant.ofEpochSecond(seconds.longValue()) : null;
            return signedIn != null && !signedIn.isBefore(clock.instant().minus(FRESH));
        });
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("https://securetoken.google.com/" + projectId),
                audience,
                fresh);
    }
}
