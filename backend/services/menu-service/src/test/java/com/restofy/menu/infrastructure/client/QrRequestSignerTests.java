package com.restofy.menu.infrastructure.client;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The credential carries exactly what QR's Menu filter demands, and nothing it forbids. */
class QrRequestSignerTests {

    private static final KeyPair KEYS = Jwts.SIG.RS256.keyPair().build();
    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final byte[] BODY = "{\"guestToken\":\"A\"}".getBytes(StandardCharsets.UTF_8);
    private static final String TARGET = "/api/v1/internal/qr/menu/guest-sessions/validate";

    private final QrRequestSigner signer = new QrRequestSigner(Clock.fixed(NOW, ZoneOffset.UTC), "menu-key-1",
            (RSAPrivateKey) KEYS.getPrivate());

    private String jwt() {
        String header = signer.authorization("POST", TARGET, BODY);
        assertTrue(header.startsWith("Service "));
        return header.substring("Service ".length());
    }

    private Claims claims() {
        return Jwts.parser().verifyWith((RSAPublicKey) KEYS.getPublic()).clock(() -> java.util.Date.from(NOW)).build()
                .parseSignedClaims(jwt()).getPayload();
    }

    @Test
    void theHeaderIsRs256WithTheKeyId() {
        var header = Jwts.parser().verifyWith((RSAPublicKey) KEYS.getPublic()).clock(() -> java.util.Date.from(NOW)).build()
                .parseSignedClaims(jwt()).getHeader();
        assertEquals("RS256", header.getAlgorithm());
        assertEquals("menu-key-1", header.getKeyId());
    }

    @Test
    void theIdentityClaimsAreMenusOwn() {
        Claims claims = claims();
        assertEquals("restofy-menu", claims.getIssuer());
        assertEquals("menu-service", claims.getSubject());
        assertEquals(java.util.Set.of("restofy-qr"), claims.getAudience());
        assertEquals("service", claims.get("token_type"));
        assertEquals("qr:menu-guest-validate", claims.get("scope"));
    }

    @Test
    void thereIsNoRestaurantClaimBecauseQrRefusesOne() {
        Claims claims = claims();
        assertFalse(claims.containsKey("restaurant_id"));
        assertFalse(claims.containsKey("restaurantId"));
    }

    @Test
    void theLifetimeIsAtMostThirtySecondsWithIatEqualToNbfAndNotInTheFuture() {
        Claims claims = claims();
        assertEquals(claims.getIssuedAt(), claims.getNotBefore());
        long lifetime = claims.getExpiration().toInstant().getEpochSecond() - claims.getIssuedAt().toInstant().getEpochSecond();
        assertTrue(lifetime > 0 && lifetime <= 30, "lifetime " + lifetime);
        assertFalse(claims.getIssuedAt().toInstant().isAfter(NOW), "iat must not be in the future");
        assertTrue(claims.getExpiration().toInstant().isAfter(NOW), "must still be valid now");
    }

    @Test
    void theIdIsAFreshCanonicalUuidEveryTime() {
        String first = claims().getId();
        String second = claims().getId();
        assertEquals(UUID.fromString(first).toString(), first);
        assertNotEquals(first, second);
    }

    @Test
    void methodTargetAndBodyDigestAreBound() {
        Claims claims = claims();
        assertEquals("POST", claims.get("method"));
        assertEquals(TARGET, claims.get("target"));
        assertEquals(QrRequestSigner.digest(BODY), claims.get("body_sha256"));
        assertEquals(64, claims.get("body_sha256").toString().length(), "SHA-256 in hex");
    }

    @Test
    void aDifferentBodyGivesADifferentDigest() {
        assertNotEquals(QrRequestSigner.digest(BODY), QrRequestSigner.digest("{\"guestToken\":\"B\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aForeignKeyDoesNotVerify() {
        KeyPair other = Jwts.SIG.RS256.keyPair().build();
        assertThrows(io.jsonwebtoken.JwtException.class, () -> Jwts.parser().verifyWith((RSAPublicKey) other.getPublic())
                .clock(() -> java.util.Date.from(NOW)).build().parseSignedClaims(jwt()));
    }
}
