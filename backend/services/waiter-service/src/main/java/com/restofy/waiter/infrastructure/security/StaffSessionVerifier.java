package com.restofy.waiter.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.infrastructure.client.DownstreamCalls;
import com.restofy.waiter.infrastructure.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Verifies the staff session of a request, in two steps that must both pass. Nothing about a staff member is
 * taken from the request itself.
 *
 * <ol>
 *   <li><b>Local proof.</b> The {@code restofy_access} JWT must be signed by Auth (RS256, the configured public
 *       key), carry the configured issuer, the {@code restofy-staff-api} audience, an access token of an
 *       {@code AUTHENTICATED} session under the {@code q5-idle-v1} policy, consistent {@code iat}/{@code nbf}/
 *       {@code exp} within Auth's lifetime, and well-formed user, restaurant, session and token ids.</li>
 *   <li><b>Freshness.</b> Auth is asked about the very same cookie on every request (the shared Auth freshness
 *       contract): a revoked or idle-expired session, a disabled account or a changed role is refused at once
 *       instead of after the token's remaining lifetime. The answer is never cached and the query does not
 *       count as staff activity.</li>
 * </ol>
 *
 * Invalid proof or a session Auth refuses is {@link Optional#empty()} (a 401 for the caller). Anything that
 * keeps Auth from answering, or an answer that cannot be trusted, throws a {@link DependencyException}: it is
 * never read as "valid" and never as "invalid". Without a configured public key nothing authenticates.
 */
@Component
public class StaffSessionVerifier {

    public static final String DEPENDENCY = "auth";
    public static final String ACCESS_COOKIE = "restofy_access";

    private static final String AUTHENTICATED = "AUTHENTICATED";
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final long START_CLOCK_SKEW_SECONDS = 3;
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private final JwtParser parser;
    private final RestClient http;
    private final URI sessionUri;
    private final Clock clock;
    private final long accessTtlSeconds;

    @SuppressWarnings("java:S2143") // jjwt's Clock contract returns java.util.Date; the time itself comes from the injected java.time Clock.
    public StaffSessionVerifier(AuthProperties properties, RestClient.Builder builder, Clock clock) {
        this.clock = clock;
        this.accessTtlSeconds = properties.accessTtlSeconds();
        if (properties.staffAuthenticationEnabled()) {
            this.parser = Jwts.parser().verifyWith(properties.rsaPublicKey())
                    .sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(START_CLOCK_SKEW_SECONDS)
                    .requireIssuer(properties.issuer()).requireAudience("restofy-staff-api")
                    .require("token_type", "access").require("auth_state", AUTHENTICATED)
                    .require("auth_policy", "q5-idle-v1").build();
            this.http = builder.clone().build();
            this.sessionUri = properties.sessionUri();
        } else {
            this.parser = null;
            this.http = null;
            this.sessionUri = null;
        }
    }

    public boolean enabled() {
        return parser != null;
    }

    public Optional<AuthenticatedStaff> verify(String credential) {
        if (!enabled() || credential == null || credential.length() > MAX_TOKEN_LENGTH
                || !JWT.matcher(credential).matches()) {
            return Optional.empty();
        }
        Verified verified;
        try {
            verified = verifiedStaff(parser.parseSignedClaims(credential).getPayload());
        } catch (JwtException | IllegalArgumentException _) {
            return Optional.empty();
        }
        return verified == null ? Optional.empty() : confirmWithAuth(credential, verified);
    }

    private Verified verifiedStaff(Claims claims) {
        if (claims.getIssuedAt() == null || claims.getExpiration() == null || claims.getNotBefore() == null) {
            return null;
        }
        Instant issuedAt = claims.getIssuedAt().toInstant();
        Instant expiry = claims.getExpiration().toInstant();
        Instant now = clock.instant();
        // JJWT also applies its skew to the expiry; the original expiry is enforced here.
        if (!claims.getNotBefore().toInstant().equals(issuedAt)
                || issuedAt.isAfter(now.plusSeconds(START_CLOCK_SKEW_SECONDS)) || !expiry.isAfter(now)
                || !expiry.isAfter(issuedAt) || expiry.isAfter(issuedAt.plusSeconds(accessTtlSeconds))) {
            return null;
        }
        if (identifier(claims.getId()) == null || identifier(claims.get("sid", String.class)) == null) return null;
        UUID userId = identifier(claims.getSubject());
        UUID restaurantId = identifier(claims.get("restaurant_id", String.class));
        StaffRole role = role(claims.get("role", String.class));
        if (userId == null || restaurantId == null || role == null) return null;
        return new Verified(new AuthenticatedStaff(userId, restaurantId, role), expiry);
    }

    private Optional<AuthenticatedStaff> confirmWithAuth(String credential, Verified verified) {
        try {
            return http.get().uri(sessionUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
                    .header(HttpHeaders.COOKIE, ACCESS_COOKIE + "=" + credential)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) return Optional.<AuthenticatedStaff>empty();
                        if (status >= 500) {
                            throw new DependencyException(DEPENDENCY, DependencyFailure.UNAVAILABLE,
                                    "Auth is unavailable for the session check");
                        }
                        if (status != 200) throw unusable("Auth answered the session check with an unexpected status");
                        return currentStaff(verified, response.bodyTo(SessionDto.class));
                    });
        } catch (RestClientException | InvalidMediaTypeException error) {
            throw error instanceof RestClientException rest ? DownstreamCalls.translate(DEPENDENCY, rest)
                    : unusable("Auth answered the session check with an unreadable response");
        }
    }

    private Optional<AuthenticatedStaff> currentStaff(Verified verified, SessionDto session) {
        if (session == null || !AUTHENTICATED.equals(session.status())
                || !verified.staff().role().name().equals(session.role()) || session.idleExpiresAt() == null
                || session.absoluteExpiresAt() == null || Boolean.TRUE.equals(session.mfaSetupRequired())) {
            throw unusable("Auth answered the session check with a session that does not match the token");
        }
        Instant now = clock.instant();
        if (!verified.expiresAt().isAfter(now) || !session.idleExpiresAt().isAfter(now)
                || !session.absoluteExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(verified.staff());
    }

    private static UUID identifier(String value) {
        if (value == null || value.length() != 36) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static StaffRole role(String value) {
        for (StaffRole role : StaffRole.values()) {
            if (role.name().equals(value)) return role;
        }
        return null;
    }

    private static DependencyException unusable(String message) {
        return new DependencyException(DEPENDENCY, DependencyFailure.BAD_RESPONSE, message);
    }

    private record Verified(AuthenticatedStaff staff, Instant expiresAt) {
    }

    /** The parts of Auth's session answer Waiter relies on; no token or credential is part of it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SessionDto(String status, String role, Instant idleExpiresAt, Instant absoluteExpiresAt,
                              Boolean mfaSetupRequired) {
    }
}
