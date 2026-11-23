package com.restofy.order.infrastructure.client;

import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
@Profile("order-qr")
class OrderQrRequestSigner {

    private final Clock clock;
    private final String keyId;
    private final RSAPrivateKey privateKey;
    private final UUID restaurantId;

    OrderQrRequestSigner(Clock clock, @Value("${ORDER_QR_CALLER_KEY_ID:}") String keyId,
                         @Value("${ORDER_QR_CALLER_PRIVATE_KEY:}") String encodedKey,
                         @Value("${ORDER_QR_CALLER_RESTAURANT_ID:}") String restaurant) {
        if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("Invalid ORDER_QR_CALLER_KEY_ID");
        }
        this.clock = clock;
        this.keyId = keyId;
        this.privateKey = readKey(encodedKey);
        this.restaurantId = readRestaurant(restaurant);
    }

    UUID restaurantId() {
        return restaurantId;
    }

    String authorization(HttpMethod method, String target, byte[] body) {
        long issuedAt = clock.instant().getEpochSecond();
        return "Service " + Jwts.builder().header().keyId(keyId).and()
                .issuer("restofy-order").subject("order-service").audience().add("restofy-qr").and()
                .id(UUID.randomUUID().toString()).claim("iat", issuedAt)
                .claim("nbf", issuedAt).claim("exp", issuedAt + 30)
                .claim("token_type", "service").claim("scope", "qr:guest-session")
                .claim("restaurant_id", restaurantId.toString())
                .claim("method", method.name()).claim("target", target).claim("body_sha256", digest(body))
                .signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    private static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static RSAPrivateKey readKey(String value) {
        try {
            var key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(value)));
            if (key instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() >= 2048) {
                return rsa;
            }
        } catch (IllegalArgumentException | GeneralSecurityException _) {
            // Startup errors must never contain the input or the parser's diagnostic.
        }
        throw new IllegalStateException("ORDER_QR_CALLER_PRIVATE_KEY must be a Base64 PKCS#8 RSA key of at least 2048 bits");
    }

    private static UUID readRestaurant(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equals(value)) {
                return id;
            }
        } catch (IllegalArgumentException _) {
            // Report configuration names without echoing values.
        }
        throw new IllegalStateException("Invalid ORDER_QR_CALLER_RESTAURANT_ID");
    }
}
