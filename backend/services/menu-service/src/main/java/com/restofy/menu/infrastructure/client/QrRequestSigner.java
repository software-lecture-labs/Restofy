package com.restofy.menu.infrastructure.client;

import io.jsonwebtoken.Jwts;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Builds the request-bound service credential Menu presents to QR's guest-session check (QR-REQUEST-01, answered in PR
 * #65): {@code Authorization: Service <JWT>}, RS256 with a {@code kid}, signed with Menu's OWN private key (Auth is not
 * involved and no key is shared with Order or Waiter).
 *
 * <p>The claims are exactly what QR's {@code MenuCallerFilter} demands: issuer {@code restofy-menu}, subject
 * {@code menu-service}, audience {@code ["restofy-qr"]}, {@code token_type=service}, scope {@code qr:menu-guest-validate},
 * a UUID {@code jti}, {@code iat == nbf}, a lifetime of at most 30 seconds, and the method, the target path and the SHA-256
 * of the exact body. There is <b>no {@code restaurant_id}</b>: Menu does not know the restaurant before asking and QR refuses
 * a credential that names one.
 *
 * <p>{@code iat} is set a few seconds in the past so that a Menu clock slightly ahead of QR's is not read as a credential
 * "from the future" (QR rejects those); the 30 second ceiling still holds from {@code iat}.
 */
final class QrRequestSigner {

    static final String ISSUER = "restofy-menu";
    static final String SUBJECT = "menu-service";
    static final String AUDIENCE = "restofy-qr";
    static final String SCOPE = "qr:menu-guest-validate";
    static final long LIFETIME_SECONDS = 30;
    static final long CLOCK_SKEW_ALLOWANCE_SECONDS = 5;

    private final Clock clock;
    private final String keyId;
    private final RSAPrivateKey privateKey;

    QrRequestSigner(Clock clock, String keyId, RSAPrivateKey privateKey) {
        this.clock = clock;
        this.keyId = keyId;
        this.privateKey = privateKey;
    }

    /** The complete header value, scheme included. It is a secret for its short life: never log it. */
    String authorization(String method, String target, byte[] body) {
        long issuedAt = clock.instant().getEpochSecond() - CLOCK_SKEW_ALLOWANCE_SECONDS;
        return "Service " + Jwts.builder().header().keyId(keyId).and()
                .issuer(ISSUER).subject(SUBJECT).audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString())
                .claim("iat", issuedAt).claim("nbf", issuedAt).claim("exp", issuedAt + LIFETIME_SECONDS)
                .claim("token_type", "service").claim("scope", SCOPE)
                .claim("method", method).claim("target", target).claim("body_sha256", digest(body))
                .signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
