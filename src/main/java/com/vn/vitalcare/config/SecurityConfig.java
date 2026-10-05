package com.vn.vitalcare.config;

import com.vn.vitalcare.identity.auth.service.AuthorizationService;
import com.vn.vitalcare.share.security.JwtProperties;
import com.vn.vitalcare.share.security.JwtService;
import com.vn.vitalcare.share.security.RestAuthenticationEntryPoint;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.convert.converter.Converter;

/**
 * Stateless bearer-token security.
 *
 * <p>Every request carries its own proof in an {@code Authorization} header, so
 * there is no session to keep, no session cookie to protect and — this is why
 * CSRF is disabled rather than merely inconvenient — no ambient credential a
 * cross-site request could ride on. The one thing that follows from that: a
 * stolen access token is enough on its own, which is what keeps its lifetime
 * short and revocation on the refresh token (see {@code AuthService}).
 *
 * <p>Stateless is about the <em>session</em>, not about authorisation. The token
 * says who the caller is; what they may do is read per request from the database
 * by {@link AuthorizationService}, so the token stays a constant size and the
 * answer stays current.
 *
 * <p>Authorisation itself is not configured here beyond "you must be signed
 * in". Which permission each endpoint needs is declared on the endpoint with
 * {@code @PreAuthorize}, enabled by {@code @EnableMethodSecurity}, so the rule
 * and the thing it guards cannot drift apart in separate files.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtProperties properties;

    public SecurityConfig(JwtProperties properties) {
        if (properties.secret() == null
                || properties.secret().getBytes(StandardCharsets.UTF_8).length < JwtProperties.MIN_SECRET_LENGTH) {
            // Failing at startup rather than signing tokens with a key that a
            // dictionary attack would recover: a weak key here is not a
            // degraded mode, it is no security at all.
            throw new IllegalStateException(
                    "app.security.jwt.secret must be at least %d bytes for HS256"
                            .formatted(JwtProperties.MIN_SECRET_LENGTH));
        }
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

        http
                // No cookie is ever used to authenticate, so there is nothing a
                // forged cross-site request could borrow.
                .csrf(csrf -> csrf.disable())
                // Defers to the CORS mappings in WebMvcConfig, which is what
                // keeps the allowed origins and the exposed X-Total-Count
                // header declared in exactly one place.
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Preflight carries no Authorization header by
                        // definition; rejecting it would fail every
                        // cross-origin request before the real one is sent.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Signing in, renewing and signing out are the three
                        // things you must be able to do without a valid access
                        // token. Everything else needs one.
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // Self-registration happens before there is a token to
                        // hold. Its own limits (one-time code, resend cooldown,
                        // per-hour cap) are what guard it instead.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/register/otp").permitAll()
                        // The WebSocket handshake, and only the handshake. The
                        // browser cannot put an Authorization header on it, so
                        // there is nothing here for this chain to check;
                        // authentication happens one frame later, on STOMP
                        // CONNECT, in StompAuthChannelInterceptor. Opening a
                        // socket therefore proves nothing and grants nothing —
                        // every SUBSCRIBE and every message sent over it is
                        // authorised against the account named in that frame.
                        .requestMatchers(WebSocketConfig.ENDPOINT + "/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(authenticationEntryPoint))
                // Nothing here serves a browser directly, so the form-login and
                // basic-auth defaults would only ever produce a login page or a
                // browser credentials prompt where the client expects JSON.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable());

        return http.build();
    }

    /**
     * The signing key, derived from the configured secret.
     *
     * <p>Symmetric (HS256) because the same application both mints and verifies
     * the token. An asymmetric key would only start to earn its keep once a
     * second service had to verify tokens without being able to issue them.
     */
    @Bean
    public SecretKey jwtSecretKey() {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /**
     * Verifies signature, expiry and issuer.
     *
     * <p>Pinning the algorithm matters: without it a token could name its own,
     * which is how the classic {@code alg: none} confusion gets in.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // The default validator covers expiry and not-before; adding the issuer
        // makes a token minted by some other service holding the same secret
        // fail here rather than being accepted as one of ours.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Builds the request's authorities from the account the token names.
     *
     * <p>The token carries no roles and no permission codes — see
     * {@link JwtService} for why — so this asks {@link AuthorizationService},
     * which reads them from the database on each request. Permission codes
     * become authorities verbatim, so a rule reads
     * {@code hasAuthority("users:write")}; role codes are granted alongside them
     * with the {@code ROLE_} prefix {@code hasRole} expects.
     *
     * <p>Resolving here rather than from claims is also what lets a token naming
     * a deleted or disabled account be rejected outright instead of being
     * honoured until it expires.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(AuthorizationService authorizationService) {
        Converter<Jwt, Collection<GrantedAuthority>> authorities =
                jwt -> authorizationService.authoritiesOf(jwt.getSubject());

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        // Authentication.getName() then reads the user id, matching CurrentUser.
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    /**
     * BCrypt at its default strength.
     *
     * <p>The encoded value records the algorithm and cost it was produced with,
     * so raising the strength later re-hashes gradually rather than invalidating
     * every existing password at once.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
