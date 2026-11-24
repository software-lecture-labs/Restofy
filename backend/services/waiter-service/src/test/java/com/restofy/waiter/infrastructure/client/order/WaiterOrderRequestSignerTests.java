package com.restofy.waiter.infrastructure.client.order;

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

/** Waiter's service credential towards Order: the claims Order's filter checks, and a start-up that never leaks the key. */
class WaiterOrderRequestSignerTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ORDER_ID = UUID.fromString("5b0d1c2e-7a3f-4c1b-9d52-0a6e8f3b4c71");
    private static final String TARGET = "/api/v1/internal/orders/" + ORDER_ID + "/serve";
    private static final UUID RESTAURANT = UUID.fromString("3f1c5a52-0a53-4f0e-9a64-6c1d2f7a9b10");
    private static final String STAFF = "header.payload.signature";

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
        assertThat(new WaiterOrderRequestSigner(CLOCK, "", "")
                .authorization(RESTAURANT, HttpMethod.POST, TARGET, new byte[0], STAFF)).isEmpty();
    }

    @Test
    void signsWaitersIdentityBoundToTheRequestTheRestaurantAndTheDelegatedStaffCredential() {
        byte[] body = "{\"commandId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        var signer = new WaiterOrderRequestSigner(CLOCK, "waiter-key-1", encodedPrivateKey);

        String header = signer.authorization(RESTAURANT, HttpMethod.POST, TARGET, body, STAFF).orElseThrow();

        assertThat(header).startsWith("Service ");
        Jws<Claims> jws = Jwts.parser().verifyWith((RSAPublicKey) pair.getPublic())
                .clock(() -> java.util.Date.from(CLOCK.instant())).build()
                .parseSignedClaims(header.substring("Service ".length()));
        Claims claims = jws.getPayload();
        assertThat(jws.getHeader().getKeyId()).isEqualTo("waiter-key-1");
        assertThat(claims.getIssuer()).isEqualTo("restofy-waiter");
        assertThat(claims.getSubject()).isEqualTo("waiter-service");
        assertThat(claims.getAudience()).containsExactly("restofy-order");
        assertThat(claims.get("token_type")).isEqualTo("service");
        assertThat(claims.get("scope")).isEqualTo("order:serve");
        assertThat(claims.get("restaurant_id")).isEqualTo(RESTAURANT.toString());
        assertThat(claims.get("method")).isEqualTo("POST");
        assertThat(claims.get("target")).isEqualTo(TARGET);
        assertThat(claims.get("body_sha256")).isEqualTo(sha256(body));
        assertThat(claims.get("staff_sha256")).isEqualTo(sha256(STAFF.getBytes(StandardCharsets.UTF_8)));
        assertThat(claims.getIssuedAt()).isEqualTo(claims.getNotBefore());
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(CLOCK.instant());
        assertThat(claims.getExpiration().toInstant()).isEqualTo(CLOCK.instant().plusSeconds(30));
        assertThat(UUID.fromString(claims.getId()).toString()).isEqualTo(claims.getId());
    }

    @Test
    void theResultQueryIsSignedWithAnEmptyBodyDigest() {
        var signer = new WaiterOrderRequestSigner(CLOCK, "waiter-key-1", encodedPrivateKey);
        String target = "/api/v1/internal/orders/" + ORDER_ID + "/serve-commands/" + UUID.randomUUID()
                + "?restaurantId=" + RESTAURANT;

        String header = signer.authorization(RESTAURANT, HttpMethod.GET, target, new byte[0], STAFF).orElseThrow();

        Claims claims = Jwts.parser().verifyWith((RSAPublicKey) pair.getPublic())
                .clock(() -> java.util.Date.from(CLOCK.instant())).build()
                .parseSignedClaims(header.substring("Service ".length())).getPayload();
        assertThat(claims.get("method")).isEqualTo("GET");
        assertThat(claims.get("target")).isEqualTo(target);
        assertThat(claims.get("body_sha256")).isEqualTo(sha256(new byte[0]));
    }

    @Test
    void everyCredentialHasItsOwnIdentifier() {
        var signer = new WaiterOrderRequestSigner(CLOCK, "waiter-key-1", encodedPrivateKey);

        assertThat(signer.authorization(RESTAURANT, HttpMethod.POST, TARGET, new byte[0], STAFF))
                .isNotEqualTo(signer.authorization(RESTAURANT, HttpMethod.POST, TARGET, new byte[0], STAFF));
    }

    @Test
    void aPartlyFilledConfigurationStopsTheStartUpWithoutEchoingValues() {
        assertThatThrownBy(() -> new WaiterOrderRequestSigner(CLOCK, "waiter-key-1", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAITER_ORDER_CALLER_PRIVATE_KEY")
                .hasMessageNotContaining("waiter-key-1");
        assertThatThrownBy(() -> new WaiterOrderRequestSigner(CLOCK, "", encodedPrivateKey))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WAITER_ORDER_CALLER_KEY_ID");
    }

    @Test
    void rejectsMalformedValues() {
        assertThatThrownBy(() -> new WaiterOrderRequestSigner(CLOCK, "bad key!", encodedPrivateKey))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WAITER_ORDER_CALLER_KEY_ID");
        assertThatThrownBy(() -> new WaiterOrderRequestSigner(CLOCK, "k", "not-base64!!"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-base64");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
