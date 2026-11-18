package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Dedicated QR-to-Order trust. Keys and tenant grants are supplied by the deployment. */
@Component
@Profile("order-qr")
public class QrServiceIdentityVerifier implements ServiceIdentityPort {

    static final int MAX_TOKEN_LENGTH = 4096;
    private static final String SCOPE = "table-session:lifecycle";
    private static final String ISSUER = "restofy-qr";
    private static final String AUDIENCE = "restofy-order";
    private static final int TTL_SECONDS = 30;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Clock clock;
    private final JwtParser parser;
    private final Set<UUID> allowedRestaurants;

    public QrServiceIdentityVerifier(Clock clock, Environment environment, JsonMapper json,
                                     @Value("${ORDER_QR_SERVICE_PUBLIC_KEYS:}") String configuredKeys,
                                     @Value("${ORDER_QR_SERVICE_RESTAURANTS:}") String configuredRestaurants) {
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("order-qr cannot be combined with dev or test");
        }
        this.clock = clock;
        Map<String, RSAPublicKey> keys = readKeys(json, configuredKeys);
        try {
            allowedRestaurants = Arrays.stream(configuredRestaurants.split(",", -1))
                    .map(String::trim).map(QrServiceIdentityVerifier::uuid).collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException _) {
            throw new IllegalStateException("ORDER_QR_SERVICE_RESTAURANTS must list authorised restaurant UUIDs");
        }
        parser = Jwts.parser().keyLocator(header -> {
                    RSAPublicKey key = header.get("kid") instanceof String kid ? keys.get(kid) : null;
                    if (key == null) {
                        throw new JwtException("Unknown key identifier");
                    }
                    return key;
                })
                .sig().clear().add(Jwts.SIG.RS256).and()
                .clock(() -> java.util.Date.from(clock.instant()))
                .requireIssuer(ISSUER).requireAudience(AUDIENCE)
                .require("token_type", "service").build();
    }

    @Override
    public Optional<ServiceActor> resolveService(String credential) {
        try {
            Claims claims = parser.parseSignedClaims(credential).getPayload();
            if (!validLifetime(claims)) {
                return Optional.empty();
            }
            uuid(claims.getId());
            if (!"qr-service".equals(claims.getSubject()) || !SCOPE.equals(claims.get("scope", String.class))) {
                throw denied();
            }
            UUID restaurant = uuid(claims.get("restaurant_id", String.class));
            if (!allowedRestaurants.contains(restaurant)) {
                throw denied();
            }
            String method = claims.get("method", String.class);
            String target = claims.get("target", String.class);
            String bodyDigest = claims.get("body_sha256", String.class);
            if (!("GET".equals(method) || "POST".equals(method)) || target == null
                    || !target.startsWith("/api/v1/internal/table-sessions/")
                    || bodyDigest == null || !bodyDigest.matches("[0-9a-f]{64}")) {
                return Optional.empty();
            }
            return Optional.of(new ServiceActor(ServiceName.QR,
                    new ServiceActor.QrRequest(restaurant, method, target, bodyDigest)));
        } catch (JwtException | IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private boolean validLifetime(Claims claims) {
        var issued = claims.getIssuedAt();
        var notBefore = claims.getNotBefore();
        var expires = claims.getExpiration();
        if (issued == null || notBefore == null || expires == null || !issued.equals(notBefore)) {
            return false;
        }
        Instant now = clock.instant();
        Instant issuedAt = issued.toInstant();
        Instant expiration = expires.toInstant();
        return !issuedAt.isAfter(now) && now.isBefore(expiration)
                && expiration.isAfter(issuedAt) && !expiration.isAfter(issuedAt.plusSeconds(TTL_SECONDS));
    }

    private static UUID uuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing UUID");
        }
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("Invalid UUID format");
        }
        return uuid;
    }

    private static BusinessException denied() {
        return new BusinessException(ErrorCode.ACCESS_DENIED, "Service is not authorised for QR lifecycle access");
    }

    private static Map<String, RSAPublicKey> readKeys(JsonMapper json, String configuredKeys) {
        try {
            Map<String, String> entries = json.readValue(configuredKeys, new TypeReference<>() {});
            if (entries.isEmpty()) {
                throw new IllegalArgumentException();
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            Map<String, RSAPublicKey> keys = new HashMap<>();
            for (var entry : entries.entrySet()) {
                RSAPublicKey key = (RSAPublicKey) factory.generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(entry.getValue())));
                if (key.getModulus().bitLength() < 2048 || !KEY_ID.matcher(entry.getKey()).matches()) {
                    throw new IllegalArgumentException();
                }
                keys.put(entry.getKey(), key);
            }
            return Map.copyOf(keys);
        } catch (Exception _) {
            throw new IllegalStateException("ORDER_QR_SERVICE_PUBLIC_KEYS must contain RSA public keys of at least 2048 bits");
        }
    }
}
