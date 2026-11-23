package com.restofy.waiter.infrastructure.client.qr;

import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Signs Waiter's own service identity for QR's internal guest validation (PR #41: issuer {@code restofy-waiter},
 * subject {@code waiter-service}, scope {@code qr:guest-validate}). The credential is bound to one request (method,
 * target, body digest) and to the restaurant QR serves, and it lives for 30 seconds at most.
 *
 * <p>The key comes only from configuration ({@code WAITER_QR_CALLER_KEY_ID}, {@code WAITER_QR_CALLER_PRIVATE_KEY},
 * {@code WAITER_QR_CALLER_RESTAURANT_ID}). While all three are blank the signer is off and no credential is sent, so
 * QR answers 401: nothing is guessed and no key is invented. A partly filled configuration stops the start-up. The
 * private key and the other values are never logged and never part of an error message.
 */
@Component
public class WaiterQrRequestSigner {

    static final String ISSUER = "restofy-waiter";
    static final String SUBJECT = "waiter-service";
    static final String AUDIENCE = "restofy-qr";
    static final String SCOPE = "qr:guest-validate";
    static final long LIFETIME_SECONDS = 30;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Clock clock;
    private final Credentials credentials;

    WaiterQrRequestSigner(Clock clock, @Value("${WAITER_QR_CALLER_KEY_ID:}") String keyId,
                          @Value("${WAITER_QR_CALLER_PRIVATE_KEY:}") String encodedKey,
                          @Value("${WAITER_QR_CALLER_RESTAURANT_ID:}") String restaurant) {
        this.clock = clock;
        String id = keyId == null ? "" : keyId.strip();
        String key = encodedKey == null ? "" : encodedKey.strip();
        String tenant = restaurant == null ? "" : restaurant.strip();
        this.credentials = id.isEmpty() && key.isEmpty() && tenant.isEmpty() ? null
                : new Credentials(readKeyId(id), readKey(key), readRestaurant(tenant));
    }

    /** The {@code Authorization} value for this request, or empty while Waiter has no service key configured. */
    Optional<String> authorization(HttpMethod method, String target, byte[] body) {
        if (credentials == null) {
            return Optional.empty();
        }
        long issuedAt = clock.instant().getEpochSecond();
        return Optional.of("Service " + Jwts.builder().header().keyId(credentials.keyId()).and()
                .issuer(ISSUER).subject(SUBJECT).audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString()).claim("iat", issuedAt)
                .claim("nbf", issuedAt).claim("exp", issuedAt + LIFETIME_SECONDS)
                .claim("token_type", "service").claim("scope", SCOPE)
                .claim("restaurant_id", credentials.restaurantId().toString())
                .claim("method", method.name()).claim("target", target).claim("body_sha256", digest(body))
                .signWith(credentials.privateKey(), Jwts.SIG.RS256).compact());
    }

    private static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String readKeyId(String value) {
        if (!KEY_ID.matcher(value).matches()) {
            throw new IllegalStateException("Invalid WAITER_QR_CALLER_KEY_ID");
        }
        return value;
    }

    private static RSAPrivateKey readKey(String value) {
        try {
            var key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(value)));
            if (key instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() >= 2048) {
                return rsa;
            }
        } catch (IllegalArgumentException | GeneralSecurityException _) {
            // Start-up errors must never contain the input or the parser's diagnostic.
        }
        throw new IllegalStateException(
                "WAITER_QR_CALLER_PRIVATE_KEY must be a Base64 PKCS#8 RSA key of at least 2048 bits");
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
        throw new IllegalStateException("Invalid WAITER_QR_CALLER_RESTAURANT_ID");
    }

    private record Credentials(String keyId, RSAPrivateKey privateKey, UUID restaurantId) {

        @Override
        public String toString() {
            return "Credentials[<redacted>]";
        }
    }
}
