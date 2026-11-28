package com.restofy.waiter.infrastructure.client.qr;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Waiter's service credential towards QR: the claims QR's filter checks, and a start-up that never leaks the key. */
class WaiterQrRequestSignerTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);
    private static final String TARGET = "/api/v1/internal/qr/guest-sessions/validate";
    private static final UUID RESTAURANT = UUID.fromString("3f1c5a52-0a53-4f0e-9a64-6c1d2f7a9b10");

    private static KeyPair pair;
    private static String encodedPrivateKey;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        encodedPrivateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }

    @Test
    void blankConfigurationMeansNothingIsSigned() {
        assertThat(new WaiterQrRequestSigner(CLOCK, "", "", "").authorization(HttpMethod.POST, TARGET, new byte[0]))
                .isEmpty();
    }

    @Test
    void signsWaitersIdentityBoundToTheRequest() {
        byte[] body = "{\"guestToken\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        var signer = new WaiterQrRequestSigner(CLOCK, "waiter-key-1", encodedPrivateKey, RESTAURANT.toString());

        String header = signer.authorization(HttpMethod.POST, TARGET, body).orElseThrow();

        assertThat(header).startsWith("Service ");
        Jws<Claims> jws = Jwts.parser().verifyWith((RSAPublicKey) pair.getPublic())
                .clock(() -> java.util.Date.from(CLOCK.instant())).build()
                .parseSignedClaims(header.substring("Service ".length()));
        Claims claims = jws.getPayload();
        assertThat(jws.getHeader().getKeyId()).isEqualTo("waiter-key-1");
        assertThat(claims.getIssuer()).isEqualTo("restofy-waiter");
        assertThat(claims.getSubject()).isEqualTo("waiter-service");
        assertThat(claims.getAudience()).containsExactly("restofy-qr");
        assertThat(claims.get("token_type")).isEqualTo("service");
        assertThat(claims.get("scope")).isEqualTo("qr:guest-validate");
        assertThat(claims.get("restaurant_id")).isEqualTo(RESTAURANT.toString());
        assertThat(claims.get("method")).isEqualTo("POST");
        assertThat(claims.get("target")).isEqualTo(TARGET);
        assertThat(claims.get("body_sha256")).isEqualTo(sha256(body));
        assertThat(claims.getIssuedAt()).isEqualTo(claims.getNotBefore());
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(CLOCK.instant());
        assertThat(claims.getExpiration().toInstant()).isEqualTo(CLOCK.instant().plusSeconds(30));
        assertThat(UUID.fromString(claims.getId()).toString()).isEqualTo(claims.getId());
    }

    @Test
    void everyCredentialHasItsOwnIdentifier() {
        var signer = new WaiterQrRequestSigner(CLOCK, "waiter-key-1", encodedPrivateKey, RESTAURANT.toString());

        assertThat(signer.authorization(HttpMethod.POST, TARGET, new byte[0]))
                .isNotEqualTo(signer.authorization(HttpMethod.POST, TARGET, new byte[0]));
    }

    @Test
    void aPartlyFilledConfigurationStopsTheStartUpWithoutEchoingValues() {
        assertThatThrownBy(() -> new WaiterQrRequestSigner(CLOCK, "waiter-key-1", "", RESTAURANT.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAITER_QR_CALLER_PRIVATE_KEY")
                .hasMessageNotContaining("waiter-key-1");
        assertThatThrownBy(() -> new WaiterQrRequestSigner(CLOCK, "", encodedPrivateKey, RESTAURANT.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WAITER_QR_CALLER_KEY_ID");
        assertThatThrownBy(() -> new WaiterQrRequestSigner(CLOCK, "waiter-key-1", encodedPrivateKey, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WAITER_QR_CALLER_RESTAURANT_ID");
    }

    @Test
    void rejectsMalformedValues() {
        assertThatThrownBy(() -> new WaiterQrRequestSigner(CLOCK, "bad key!", encodedPrivateKey, RESTAURANT.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WAITER_QR_CALLER_KEY_ID");
        assertThatThrownBy(() -> new WaiterQrRequestSigner(CLOCK, "k", "not-base64!!", RESTAURANT.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-base64");
        assertThatThrownBy(() -> new WaiterQrRequestSigner(CLOCK, "k", encodedPrivateKey, "not-a-uuid"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-a-uuid");
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
