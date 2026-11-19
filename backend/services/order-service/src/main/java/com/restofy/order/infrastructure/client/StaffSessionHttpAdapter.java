package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Profile("order-auth")
public class StaffSessionHttpAdapter implements AuthContextPort {
    private static final String AUTHENTICATED = "AUTHENTICATED";
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final long START_CLOCK_SKEW_SECONDS = 3;
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private final URI sessionUri;
    private final JwtParser parser;
    private final RestClient http;
    private final Clock clock;
    private final long accessTtl;

    StaffSessionHttpAdapter(@Value("${AUTH_SERVICE_URL:}") String base,
                            @Value("${ORDER_AUTH_STAFF_PUBLIC_KEY:}") String publicKey,
                            @Value("${JWT_ISSUER:restofy-auth}") String issuer,
                            @Value("${JWT_ACCESS_TTL_SECONDS:300}") long accessTtl,
                            RestClient.Builder http, Clock clock) {
        this.sessionUri = sessionUri(base);
        if (issuer.isBlank() || accessTtl <= 0 || accessTtl > 300) {
            throw new IllegalStateException("Order staff JWT issuer or lifetime is invalid");
        }
        this.parser = Jwts.parser().verifyWith(rsaKey(publicKey))
                .sig().clear().add(Jwts.SIG.RS256).and()
                .clock(() -> java.util.Date.from(clock.instant()))
                .clockSkewSeconds(START_CLOCK_SKEW_SECONDS)
                .requireIssuer(issuer).requireAudience("restofy-staff-api")
                .require("token_type", "access").require("auth_state", AUTHENTICATED)
                .require("auth_policy", "q5-idle-v1").build();
        this.http = http.build();
        this.clock = clock;
        this.accessTtl = accessTtl;
    }

    @Override
    public Optional<StaffActor> resolveStaff(String credential) {
        if (credential == null || credential.length() > MAX_TOKEN_LENGTH || !JWT.matcher(credential).matches()) {
            return Optional.empty();
        }
        VerifiedStaff staff;
        try {
            Claims claims = parser.parseSignedClaims(credential).getPayload();
            staff = verifiedStaff(claims);
        } catch (JwtException | IllegalArgumentException _) {
            return Optional.empty();
        }
        return staff == null ? Optional.empty() : confirmSession(credential, staff);
    }

    private VerifiedStaff verifiedStaff(Claims claims) {
        var issuedAtClaim = claims.getIssuedAt();
        var expiryClaim = claims.getExpiration();
        var notBeforeClaim = claims.getNotBefore();
        if (issuedAtClaim == null || expiryClaim == null || notBeforeClaim == null) {
            return null;
        }
        Instant issuedAt = issuedAtClaim.toInstant();
        Instant expiry = expiryClaim.toInstant();
        Instant notBefore = notBeforeClaim.toInstant();
        Instant now = clock.instant();
        // JJWT also applies its skew to expiration. Enforce the original expiry here.
        if (!notBefore.equals(issuedAt) || issuedAt.isAfter(now.plusSeconds(START_CLOCK_SKEW_SECONDS)) || !expiry.isAfter(now)
                || !expiry.isAfter(issuedAt) || expiry.isAfter(issuedAt.plusSeconds(accessTtl))) {
            return null;
        }
        if (identifier(claims.getId()) == null || identifier(claims.get("sid", String.class)) == null) {
            return null;
        }
        UUID userId = identifier(claims.getSubject());
        UUID restaurantId = identifier(claims.get("restaurant_id", String.class));
        String role = claims.get("role", String.class);
        if (userId == null || restaurantId == null || role == null) {
            return null;
        }
        return new VerifiedStaff(new StaffActor(userId, restaurantId, StaffRole.valueOf(role)), expiry);
    }

    private Optional<StaffActor> confirmSession(String credential, VerifiedStaff staff) {
        try {
            return http.get().uri(sessionUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
                    .header(HttpHeaders.COOKIE, "restofy_access=" + credential)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) {
                            return Optional.empty();
                        }
                        if (status != 200) {
                            throw unavailable();
                        }
                        return currentStaff(staff, response.bodyTo(SessionResponse.class));
                    });
        } catch (RestClientException | InvalidMediaTypeException _) {
            throw unavailable();
        }
    }

    private Optional<StaffActor> currentStaff(VerifiedStaff staff, SessionResponse session) {
        if (session == null || !AUTHENTICATED.equals(session.status())
                || !staff.actor().role().name().equals(session.role()) || session.idleExpiresAt() == null
                || session.absoluteExpiresAt() == null || Boolean.TRUE.equals(session.mfaSetupRequired())) {
            throw unavailable();
        }
        Instant now = clock.instant();
        if (!staff.expiresAt().isAfter(now) || !session.idleExpiresAt().isAfter(now)
                || !session.absoluteExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(staff.actor());
    }

    private static UUID identifier(String value) {
        return value == null || value.length() != 36 ? null : UUID.fromString(value);
    }

    private static URI sessionUri(String base) {
        try {
            URI uri = URI.create(base);
            int port = uri.getPort();
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && (port == -1 || (port > 0 && port <= 65535))
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && ("".equals(uri.getRawPath()) || "/".equals(uri.getRawPath()))) {
                return UriComponentsBuilder.fromUri(uri).replacePath(null)
                        .pathSegment("api", "v1", "auth", "session").build().toUri();
            }
        } catch (IllegalArgumentException _) {
            // Do not include configuration values in startup errors.
        }
        throw new IllegalStateException("AUTH_SERVICE_URL must be an HTTPS origin for order-auth");
    }

    private static RSAPublicKey rsaKey(String encoded) {
        try {
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= 2048) {
                return rsa;
            }
        } catch (GeneralSecurityException | IllegalArgumentException _) {
            // Do not include key material or its parsing error.
        }
        throw new IllegalStateException("ORDER_AUTH_STAFF_PUBLIC_KEY must be a Base64 X.509 RSA public key of at least 2048 bits");
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Staff authentication is currently unavailable");
    }

    private record VerifiedStaff(StaffActor actor, Instant expiresAt) {
    }

    private record SessionResponse(String status, String role, Instant idleExpiresAt, Instant absoluteExpiresAt,
                                   Boolean mfaSetupRequired) {
    }
}
