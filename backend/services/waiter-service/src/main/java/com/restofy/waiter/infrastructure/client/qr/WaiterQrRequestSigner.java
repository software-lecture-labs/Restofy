package com.restofy.waiter.infrastructure.client.qr;

import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
 * Signs Waiter's own service identity for QR's closed internal routes (PR #41 and #51: issuer
 * {@code restofy-waiter}, subject {@code waiter-service}, one scope per route family). The credential is bound to one request (method,
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
    /** The scopes QR (PR #41, #51) lets Waiter present; a credential carries exactly one. */
    static final String GUEST_VALIDATE = "qr:guest-validate";
    static final String TABLE_READ = "qr:table-read";
    static final String SESSION_CLOSE = "qr:session-close";
    static final String TABLE_CLEAN = "qr:table-clean";
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

    /** The guest-validation credential; see {@link #authorization(String, HttpMethod, String, byte[], String)}. */
    Optional<String> authorization(HttpMethod method, String target, byte[] body) {
        return authorization(GUEST_VALIDATE, method, target, body, null);
    }

    /**
     * The {@code Authorization} value for this request, or empty while Waiter has no service key configured. The
     * close and clean scopes also need the staff member's access token (forwarded in {@code X-Staff-Token}); its
     * SHA-256 digest is bound into the credential as {@code staff_token_sha256}. The token itself is never signed in
     * clear and never logged.
     */
    Optional<String> authorization(String scope, HttpMethod method, String target, byte[] body, String staffToken) {
        if (credentials == null) {
            return Optional.empty();
        }
        long issuedAt = clock.instant().getEpochSecond();
        var builder = Jwts.builder().header().keyId(credentials.keyId()).and()
                .issuer(ISSUER).subject(SUBJECT).audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString()).claim("iat", issuedAt)
                .claim("nbf", issuedAt).claim("exp", issuedAt + LIFETIME_SECONDS)
                .claim("token_type", "service").claim("scope", scope)
                .claim("restaurant_id", credentials.restaurantId().toString())
                .claim("method", method.name()).claim("target", target).claim("body_sha256", digest(body));
        if (staffToken != null) {
            builder.claim("staff_token_sha256", digest(staffToken.getBytes(StandardCharsets.UTF_8)));
        }
        return Optional.of("Service " + builder.signWith(credentials.privateKey(), Jwts.SIG.RS256).compact());
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
