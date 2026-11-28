package com.restofy.menu.support;

import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builds the credential Order's signer produces (PR #79, {@code OrderServiceRequestSigner}): RS256 with a {@code kid},
 * {@code iss=restofy-order}, {@code sub=order-service}, {@code aud=["restofy-menu"]}, {@code token_type=service},
 * {@code scope=menu:catalog-read}, a UUID {@code jti}, {@code iat==nbf}, {@code exp=iat+30}, and {@code restaurant_id},
 * {@code method}, {@code target}, {@code body_sha256}. A test changes a claim through {@code tweak} to build exactly one flaw.
 */
public final class TestOrderCredentials {

    public static final String LOOKUP = "/api/v1/internal/menu/catalog/products:lookup";

    private TestOrderCredentials() {
    }

    public static Map<String, Object> claims(UUID restaurant, byte[] body, Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        long issuedAt = now.getEpochSecond();
        claims.put("iss", "restofy-order");
        claims.put("sub", "order-service");
        claims.put("aud", List.of("restofy-menu"));
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", issuedAt);
        claims.put("nbf", issuedAt);
        claims.put("exp", issuedAt + 30);
        claims.put("token_type", "service");
        claims.put("scope", "menu:catalog-read");
        claims.put("restaurant_id", restaurant.toString());
        claims.put("method", "POST");
        claims.put("target", LOOKUP);
        claims.put("body_sha256", digest(body));
        return claims;
    }

    /** The complete header value ({@code Service <jwt>}) signed with {@code key} under {@code kid}. */
    public static String authorization(KeyPair key, String kid, UUID restaurant, byte[] body, Instant now,
            Consumer<Map<String, Object>> tweak) {
        Map<String, Object> claims = claims(restaurant, body, now);
        tweak.accept(claims);
        return "Service " + Jwts.builder().header().keyId(kid).and().claims(claims).signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }

    public static String authorization(KeyPair key, String kid, UUID restaurant, byte[] body) {
        return authorization(key, kid, restaurant, body, Instant.now(), c -> { });
    }

    /** A well-formed credential with no signature at all ({@code alg=none}). */
    public static String unsecured(UUID restaurant, byte[] body, String kid) {
        return "Service " + Jwts.builder().header().keyId(kid).and().claims(claims(restaurant, body, Instant.now())).compact();
    }

    /** A credential signed with a shared secret (HS256), the classic algorithm-confusion attempt. */
    public static String hmac(SecretKey secret, UUID restaurant, byte[] body, String kid) {
        return "Service " + Jwts.builder().header().keyId(kid).and().claims(claims(restaurant, body, Instant.now()))
                .signWith(secret, Jwts.SIG.HS256).compact();
    }

    public static String digest(byte[] body) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] lookupBody(UUID restaurant, UUID... products) {
        StringBuilder ids = new StringBuilder();
        for (UUID product : products) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(product).append('"');
        }
        return ("{\"restaurantId\":\"" + restaurant + "\",\"productIds\":[" + ids + "]}").getBytes(StandardCharsets.UTF_8);
    }
}
