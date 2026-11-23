package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.enums.ActorRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Proves a staff access token locally, the way Auth's {@code staff-v1} contract defines it, and turns it into the
 * {@link AuthenticatedActor} the management endpoints work with. Nothing about the staff member is taken from the
 * request itself: user, restaurant and role are the claims of a token Auth signed.
 *
 * <p>A token is accepted only when every one of these holds; anything else is {@link Optional#empty()} (a 401):
 * RS256 signature with the configured Auth public key (no other algorithm, so no {@code none} and no HMAC with the
 * public key as secret), the configured issuer, the {@code restofy-staff-api} audience and no other, an access token of an
 * {@code AUTHENTICATED} session under the {@code q5-idle-v1} policy (a pending-MFA, refresh or service token is
 * none of these), {@code iat}/{@code nbf}/{@code exp}/{@code jti}/{@code sid} present, not expired, not issued in
 * the future and living at most Auth's fixed 300 seconds, well-formed user, restaurant, session and token ids, and a
 * role Menu knows. Whether that role may do something is decided by the endpoint, not here.
 *
 * <p><b>Known limit (Auth decision, not hidden):</b> the check is local. A logout, a disabled account or a changed role
 * reaches Menu when the token expires, at most 300 seconds later; Auth offers no introspection and its coordinated
 * revocation (Q5) targets QR only. Without a configured public key nothing authenticates.
 */
public final class StaffJwtVerifier {

    /** Auth's staff access token lifetime; a longer one is never accepted. */
    static final long MAX_LIFETIME_SECONDS = 300;
    static final String AUDIENCE = "restofy-staff-api";
    static final String POLICY = "q5-idle-v1";
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final int UUID_LENGTH = 36;

    private final JwtParser parser;
    private final Clock clock;

    public StaffJwtVerifier(StaffAuthProperties properties, Clock clock) {
        this.clock = clock;
        this.parser = properties.staffAuthenticationEnabled()
                ? Jwts.parser().verifyWith(properties.rsaPublicKey())
                        .sig().clear().add(Jwts.SIG.RS256).and()
                        .clock(() -> Date.from(clock.instant()))
                        .requireIssuer(properties.issuer()).requireAudience(AUDIENCE)
                        .require("token_type", "access").require("auth_state", "AUTHENTICATED")
                        .require("auth_policy", POLICY).build()
                : null;
    }

    public boolean enabled() {
        return parser != null;
    }

    public Optional<AuthenticatedActor> verify(String token) {
        if (!enabled() || token == null || token.length() > MAX_TOKEN_LENGTH || !JWT.matcher(token).matches()) {
            return Optional.empty();
        }
        try {
            return actor(parser.parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private Optional<AuthenticatedActor> actor(Claims claims) {
        if (claims.getAudience() == null || claims.getAudience().size() != 1 || claims.getIssuedAt() == null
                || claims.getNotBefore() == null || claims.getExpiration() == null
                || !lifetimeIsValid(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant())) {
            return Optional.empty();
        }
        UUID userId = identifier(claims.getSubject());
        UUID restaurantId = identifier(claims.get("restaurant_id", String.class));
        ActorRole role = role(claims.get("role", String.class));
        if (userId == null || restaurantId == null || role == null || restaurantId.equals(new UUID(0, 0))
                || identifier(claims.getId()) == null || identifier(claims.get("sid", String.class)) == null) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedActor(userId, restaurantId, role));
    }

    /** Issued in the past, not expired, a positive lifetime of at most {@value #MAX_LIFETIME_SECONDS} seconds. */
    private boolean lifetimeIsValid(Instant issuedAt, Instant expiresAt) {
        Instant now = clock.instant();
        return !issuedAt.isAfter(now) && now.isBefore(expiresAt) && expiresAt.isAfter(issuedAt)
                && !expiresAt.isAfter(issuedAt.plusSeconds(MAX_LIFETIME_SECONDS));
    }

    private static UUID identifier(String value) {
        if (value == null || value.length() != UUID_LENGTH) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static ActorRole role(String value) {
        for (ActorRole role : ActorRole.values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        return null;
    }
}
