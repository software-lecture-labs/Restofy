package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.infrastructure.config.KitchenRestaurantScope;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("order-kitchen")
class KitchenServiceIdentityVerifier {

    private static final Set<String> TARGETS = Set.of("/api/v1/internal/order-events/kitchen",
            "/api/v1/internal/order-events/kitchen-cancellation-request",
            "/api/v1/internal/order-events/kitchen-cancellation");
    private final Set<UUID> restaurants;
    private final JwtParser parser;
    private final Clock clock;

    KitchenServiceIdentityVerifier(Clock clock, Environment environment, JsonMapper json,
                                   @Value("${ORDER_KITCHEN_SERVICE_PUBLIC_KEYS:}") String publicKeys,
                                   KitchenRestaurantScope scope) {
        if (environment.matchesProfiles("dev", "test")) {
            throw new IllegalStateException("order-kitchen cannot use development or test profiles");
        }
        this.clock = clock;
        this.restaurants = Set.copyOf(scope.restaurants());
        var keys = SignedServiceKeys.read(json, publicKeys, "ORDER_KITCHEN_SERVICE_PUBLIC_KEYS");
        parser = Jwts.parser().keyLocator(header -> {
                    var key = header.get("kid") instanceof String id ? keys.get(id) : null;
                    if (key == null) {
                        throw new JwtException("Unknown Kitchen signing key");
                    }
                    return key;
                }).sig().clear().add(Jwts.SIG.RS256).and()
                .clock(() -> Date.from(clock.instant()))
                .requireIssuer("restofy-kitchen").requireSubject("kitchen-service").requireAudience("restofy-order")
                .require("token_type", "service").require("scope", "order:kitchen-events").build();
    }

    static boolean matches(String method, String path) {
        return "POST".equals(method) && TARGETS.contains(path);
    }

    Optional<ServiceActor> resolve(String token) {
        try {
            var claims = parser.parseSignedClaims(token).getPayload();
            var issued = claims.getIssuedAt();
            var expires = claims.getExpiration();
            var now = clock.instant();
            if (issued == null || expires == null || !issued.equals(claims.getNotBefore())
                    || issued.toInstant().isAfter(now) || !expires.toInstant().isAfter(now)
                    || expires.toInstant().isAfter(issued.toInstant().plusSeconds(30))) {
                return Optional.empty();
            }
            QrServiceIdentityVerifier.uuid(claims.getId());
            UUID restaurant = QrServiceIdentityVerifier.uuid(claims.get("restaurant_id", String.class));
            String method = claims.get("method", String.class);
            String target = claims.get("target", String.class);
            String digest = claims.get("body_sha256", String.class);
            if (!restaurants.contains(restaurant) || target == null || !matches(method, target)
                    || digest == null || !digest.matches("[a-f0-9]{64}")) {
                return Optional.empty();
            }
            return Optional.of(new ServiceActor(ServiceName.KITCHEN,
                    new ServiceActor.SignedRequest(restaurant, method, target, digest)));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
