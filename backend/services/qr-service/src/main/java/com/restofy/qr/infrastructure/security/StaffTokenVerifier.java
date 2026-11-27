package com.restofy.qr.infrastructure.security;

import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies an Auth-issued staff access token and the local revocation guard. Shared by the browser staff routes
 * (token from the HttpOnly cookie) and the Waiter delegation (token forwarded in a header and bound to the service
 * credential), so both accept exactly the same tokens.
 */
public final class StaffTokenVerifier {
    private static final Set<String> STAFF_ROLES = Set.of("ADMIN", "WAITER", "KITCHEN");
    private static final int MAX_TOKEN_LENGTH = 4096;

    private final Q5SecuritySettings settings;
    private final Clock clock;
    private final StaffRevocationStore store;

    public StaffTokenVerifier(Q5SecuritySettings settings, Clock clock, StaffRevocationStore store) {
        this.settings = settings;
        this.clock = clock;
        this.store = store;
    }

    /** Signature, issuer, audience, restaurant, freshness and role. Throws {@link IllegalArgumentException} on any failure. */
    public StaffRequestFilter.Principal verify(String token) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) { throw new IllegalArgumentException(); }
            UUID restaurant = settings.restaurant();
            var claims = Jwts.parser().verifyWith(settings.staffKey()).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> java.util.Date.from(clock.instant()))
                    .requireIssuer(settings.staffIssuer()).requireAudience("restofy-staff-api")
                    .require("token_type", "access").require("auth_state", "AUTHENTICATED").require("auth_policy", "q5-idle-v1")
                    .require("restaurant_id", restaurant.toString()).build().parseSignedClaims(token).getPayload();
            requireFresh(claims, settings.accessTtl());
            String role = claims.get("role", String.class);
            if (!STAFF_ROLES.contains(role)) { throw new IllegalArgumentException(); }
            return new StaffRequestFilter.Principal(restaurant, UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("sid", String.class)), role);
        } catch (Exception _) {
            throw new IllegalArgumentException("Invalid staff identity");
        }
    }

    /** The primary-database revocation guard. Throws ResponseStatusException when denied, other runtime errors when unavailable. */
    public void requireAllowed(StaffRequestFilter.Principal principal) {
        store.requireAllowed(principal.restaurantId(), principal.userId(), principal.sessionId());
    }

    /** iat/nbf/exp/jti present, issued in the past, not expired, positive lifetime of at most the fixed 300 s TTL. */
    private void requireFresh(Claims claims, long ttl) {
        if (ttl != 300 || claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null
                || claims.getId() == null) {
            throw new IllegalArgumentException();
        }
        Instant now = clock.instant();
        Instant issued = claims.getIssuedAt().toInstant();
        Instant expires = claims.getExpiration().toInstant();
        if (issued.isAfter(now) || !now.isBefore(expires) || !expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(ttl))) {
            throw new IllegalArgumentException();
        }
        UUID.fromString(claims.getId());
    }
}
