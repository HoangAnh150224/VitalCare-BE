package com.vn.vitalcare.care.registration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.vn.vitalcare.care.registration.dto.RegisterRequest;
import com.vn.vitalcare.care.registration.entity.VerificationPurpose;
import com.vn.vitalcare.care.registration.repository.PhoneVerificationRepository;
import com.vn.vitalcare.share.exception.FieldValidationException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Firebase Phone Auth: the token Firebase gives the browser is what proves the
 * number, and only a fresh one, for this project, for this very number.
 * Signed here with a key of our own; the checks are the ones used for real.
 */
class FirebasePhoneTokensTest {

    private static final String PROJECT = "vitalcare-test";
    private static final Instant NOW = Instant.now();

    private KeyPair keys;
    private FirebasePhoneTokens tokens;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        tokens = new FirebasePhoneTokens(NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build(),
                PROJECT, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String token(String issuer, String audience, String phone, Instant signedIn, Instant expires)
            throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("firebase-uid")
                .issueTime(Date.from(signedIn))
                .expirationTime(Date.from(expires))
                .claim("auth_time", signedIn.getEpochSecond());
        if (phone != null) {
            claims.claim("phone_number", phone);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    private String valid(String phone) throws Exception {
        return token("https://securetoken.google.com/" + PROJECT, PROJECT, phone, NOW.minusSeconds(30),
                NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("a fresh token for this project gives the number it proves")
    void readsTheNumber() throws Exception {
        assertEquals("+84968339201", tokens.verifiedPhone(valid("+84968339201")));
    }

    @Test
    @DisplayName("another project's, an old sign-in's, an expired, a numberless or a forged token proves nothing")
    void refusesTheRest() throws Exception {
        String google = "https://securetoken.google.com/";
        assertThrows(InvalidVerificationCodeException.class, () -> tokens.verifiedPhone(
                token(google + "other", "other", "+84968339201", NOW.minusSeconds(30), NOW.plusSeconds(3600))));
        assertThrows(InvalidVerificationCodeException.class, () -> tokens.verifiedPhone(
                token(google + PROJECT, PROJECT, "+84968339201", NOW.minus(Duration.ofMinutes(11)), NOW.plusSeconds(3600))));
        assertThrows(InvalidVerificationCodeException.class, () -> tokens.verifiedPhone(
                token(google + PROJECT, PROJECT, "+84968339201", NOW.minus(Duration.ofHours(2)), NOW.minusSeconds(60))));
        assertThrows(InvalidVerificationCodeException.class, () -> tokens.verifiedPhone(valid(null)));
        String forged = valid("+84968339201");
        assertThrows(InvalidVerificationCodeException.class,
                () -> tokens.verifiedPhone(forged.substring(0, forged.length() - 4) + "AAAA"));
    }

    @Test
    @DisplayName("with Firebase on, a code request sends nothing itself and proof is Firebase's token for that number only")
    void phoneCodesTakeTheToken() throws Exception {
        PhoneVerificationRepository verifications = mock(PhoneVerificationRepository.class);
        java.util.concurrent.atomic.AtomicReference<com.vn.vitalcare.care.registration.entity.PhoneVerification> row =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(verifications.findLatestForUpdate(any(), any())).thenAnswer(invocation -> Optional.ofNullable(row.get()));
        when(verifications.save(any())).thenAnswer(invocation -> {
            row.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        OtpSender sender = mock(OtpSender.class);
        PhoneCodes codes = PhoneCodes.withFirebase(verifications,
                new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5),
                Clock.fixed(NOW, ZoneOffset.UTC), tokens);

        assertEquals("firebase", codes.issue("+84968339201", VerificationPurpose.REGISTER, true).channel());
        verify(sender, never()).send(any(), any(), any());

        assertThrows(InvalidVerificationCodeException.class,
                () -> codes.prove("+84968339201", VerificationPurpose.REGISTER, null, valid("+84900000001")));
        String token = valid("+84968339201");
        codes.prove("+84968339201", VerificationPurpose.REGISTER, null, token);
        assertThrows(InvalidVerificationCodeException.class,
                () -> codes.prove("+84968339201", VerificationPurpose.REGISTER, null, token),
                "one token, one action: the request it answered is spent");
        assertThrows(FieldValidationException.class,
                () -> codes.prove("+84968339201", VerificationPurpose.REGISTER, "123456", null),
                "a code of ours is not proof while Firebase sends the codes");
    }

    @Test
    @DisplayName("without Firebase, the old code path still needs a code")
    void oldPathNeedsTheCode() {
        PhoneVerificationRepository verifications = mock(PhoneVerificationRepository.class);
        PhoneCodes codes = new PhoneCodes(verifications, mock(OtpSender.class),
                new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5), Clock.systemUTC());
        assertThrows(FieldValidationException.class,
                () -> codes.prove("+84968339201", VerificationPurpose.REGISTER, null, "anything"));
        new RegisterRequest("0968339201", null, "token", "A", "Passw0rd!"); // the DTO admits either proof
    }
}
