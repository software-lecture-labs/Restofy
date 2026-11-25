package com.restofy.order.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class WaiterSignatureVerifier {

    static final int MAX_TOKEN_LENGTH = 4096;
    private static final int VALIDITY_SECONDS = 30;

    record Request(UUID restaurantId, String method, String target, String bodyDigest, String staffDigest) {
    }

    private final Clock clock;
    private final JwtParser parser;
    private final Set<UUID> restaurants;

    WaiterSignatureVerifier(Clock clock, Environment environment, JsonMapper json,
                            @Value("${ORDER_WAITER_SERVICE_PUBLIC_KEYS:}") String publicKeys,
                            @Value("${ORDER_WAITER_SERVICE_RESTAURANTS:}") String restaurantIds) {
        this.clock = clock;
        if (publicKeys.isBlank() && restaurantIds.isBlank()) {
            parser = null;
            restaurants = Set.of();
            return;
        }
        if (!environment.matchesProfiles("order-auth", "test & !prod")) {
            throw new IllegalStateException("Waiter authentication requires the order-auth profile");
        }
        Map<String, RSAPublicKey> keys = SignedServiceKeys.read(json, publicKeys, "ORDER_WAITER_SERVICE_PUBLIC_KEYS");
        try {
            restaurants = Arrays.stream(restaurantIds.split(",", -1)).map(String::trim)
                    .map(WaiterSignatureVerifier::canonicalUuid).collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("ORDER_WAITER_SERVICE_RESTAURANTS must list authorised restaurant UUIDs");
        }
        parser = Jwts.parser().keyLocator(header -> {
                    RSAPublicKey key = header.get("kid") instanceof String id ? keys.get(id) : null;
                    if (key == null) {
                        throw new JwtException("Unknown Waiter key identifier");
                    }
                    return key;
                })
                .sig().clear().add(Jwts.SIG.RS256).and()
                .clock(() -> java.util.Date.from(clock.instant()))
                .requireIssuer("restofy-waiter").requireSubject("waiter-service").requireAudience("restofy-order")
                .require("token_type", "service").build();
    }

    Optional<Request> verify(String token, String expectedScope) {
        if (parser == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            if (!expectedScope.equals(claims.get("scope", String.class)) || !validLifetime(claims)) {
                return Optional.empty();
            }
            canonicalUuid(claims.getId());
            UUID restaurant = canonicalUuid(claims.get("restaurant_id", String.class));
            String method = claims.get("method", String.class);
            String target = claims.get("target", String.class);
            String body = claims.get("body_sha256", String.class);
            String staff = claims.get("staff_sha256", String.class);
            if (!restaurants.contains(restaurant) || target == null || !isDigest(body) || !isDigest(staff)) {
                return Optional.empty();
            }
            return Optional.of(new Request(restaurant, method, target, body, staff));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private boolean validLifetime(Claims claims) {
        var issued = claims.getIssuedAt();
        var expires = claims.getExpiration();
        if (issued == null || expires == null || !issued.equals(claims.getNotBefore())) {
            return false;
        }
        Instant now = clock.instant();
        return !issued.toInstant().isAfter(now) && expires.toInstant().isAfter(now)
                && !expires.toInstant().isAfter(issued.toInstant().plusSeconds(VALIDITY_SECONDS));
    }

    private static UUID canonicalUuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing UUID");
        }
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("Invalid UUID");
        }
        return uuid;
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

}
