package com.restofy.order.infrastructure.client;

import io.jsonwebtoken.Jwts;
import org.springframework.http.HttpMethod;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

final class OrderServiceRequestSigner {

    private final Clock clock;
    private final String keyId;
    private final RSAPrivateKey key;
    private final String audience;
    private final String scope;

    OrderServiceRequestSigner(Clock clock, String keyId, String encodedKey, String configurationPrefix,
                              String audience, String scope) {
        if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("Invalid " + configurationPrefix + "_KEY_ID");
        }
        this.clock = clock;
        this.keyId = keyId;
        this.key = readKey(encodedKey, configurationPrefix);
        this.audience = audience;
        this.scope = scope;
    }

    String authorization(UUID restaurantId, HttpMethod method, String target, byte[] body) {
        long issuedAt = clock.instant().getEpochSecond();
        return "Service " + Jwts.builder().header().keyId(keyId).and()
                .issuer("restofy-order").subject("order-service").audience().add(audience).and()
                .id(UUID.randomUUID().toString()).claim("iat", issuedAt).claim("nbf", issuedAt)
                .claim("exp", issuedAt + 30).claim("token_type", "service").claim("scope", scope)
                .claim("restaurant_id", restaurantId.toString()).claim("method", method.name())
                .claim("target", target).claim("body_sha256", digest(body))
                .signWith(key, Jwts.SIG.RS256).compact();
    }

    private static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static RSAPrivateKey readKey(String encodedKey, String prefix) {
        try {
            var key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encodedKey)));
            if (key instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() >= 2048) {
                return rsa;
            }
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            // Do not disclose key material or parser diagnostics.
        }
        throw new IllegalStateException(prefix + "_PRIVATE_KEY must be a Base64 PKCS#8 RSA key of at least 2048 bits");
    }
}
