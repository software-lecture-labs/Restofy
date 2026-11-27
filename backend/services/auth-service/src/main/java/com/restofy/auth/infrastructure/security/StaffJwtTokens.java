package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.domain.policy.SessionPolicy;
import com.restofy.auth.infrastructure.config.AuthSettings;
import io.jsonwebtoken.Jwts;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

@Component
public class StaffJwtTokens {
    /** Upper bound on the token text we are willing to parse, rejected before any signature work. */
    static final int MAX_TOKEN_LENGTH = 4096;
    private final AuthSettings settings;
    private final Clock clock;

    public StaffJwtTokens(AuthSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public String issue(UserSession session) {
        Instant now = clock.instant();
        Kind kind = Kind.of(session.getAuthState());
        Instant expiry = now.plusSeconds(lifetimeSeconds(kind));
        if (expiry.isAfter(session.getAbsoluteExpiresAt())) {
            expiry = session.getAbsoluteExpiresAt();
        }
        if (expiry.isAfter(session.idleExpiresAt())) expiry = session.idleExpiresAt();
        return Jwts.builder().subject(session.getStaffUserId().toString()).issuer(settings.issuer())
                .audience().add(kind.audience).and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString()).claim("sid", session.getId().toString())
                .claim("role", session.getRoleSnapshot().name()).claim("auth_state", session.getAuthState().name())
                .claim("restaurant_id", settings.restaurantId().toString())
                .claim("token_type", kind.tokenType)
                .claim("auth_policy", "q5-idle-v1")
                .signWith(settings.privateKey(), Jwts.SIG.RS256).compact();
    }

    private long lifetimeSeconds(Kind kind) {
        return switch (kind) {
            case ACCESS -> settings.accessTtl();
            case MFA_PENDING -> SessionPolicy.MFA_PENDING_TTL.toSeconds();
            case PASSWORD_PENDING -> SessionPolicy.PASSWORD_PENDING_TTL.toSeconds();
        };
    }

    /**
     * @param pending {@code false} for the access cookie, which only ever holds a usable session's token;
     *                {@code true} for the pending cookie, which holds an MFA or a password-change token
     */
    public Identity verify(String token, boolean pending) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException();
            }
            var claims = Jwts.parser().verifyWith(settings.publicKey())
                    .sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant())).requireIssuer(settings.issuer())
                    .require("restaurant_id", settings.restaurantId().toString())
                    .build().parseSignedClaims(token).getPayload();
            SessionAuthState state = SessionAuthState.valueOf(claims.get("auth_state", String.class));
            Kind kind = Kind.of(state);
            // Each state has its own audience and type, so no token can stand in for another kind.
            if ((kind == Kind.ACCESS) == pending || !Set.of(kind.audience).equals(claims.getAudience())
                    || !kind.tokenType.equals(claims.get("token_type", String.class))) {
                throw new IllegalArgumentException();
            }
            Instant now = clock.instant();
            if (claims.getExpiration() == null || claims.getIssuedAt() == null || claims.getNotBefore() == null
                    || claims.getId() == null || !now.isBefore(claims.getExpiration().toInstant())
                    || claims.getIssuedAt().toInstant().isAfter(now)
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant()
                            .isAfter(claims.getIssuedAt().toInstant().plusSeconds(lifetimeSeconds(kind)))) {
                throw new IllegalArgumentException();
            }
            StaffRole role = StaffRole.valueOf(claims.get("role", String.class));
            if (kind == Kind.MFA_PENDING && role != StaffRole.ADMIN) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(claims.getId());
            return new Identity(UUID.fromString(claims.getSubject()), UUID.fromString(claims.get("sid", String.class)),
                    role, state);
        } catch (RuntimeException _) {
            throw new BadCredentialsException("Invalid session");
        }
    }

    /** The token issued for each session state. Only {@link #ACCESS} is accepted by the other services. */
    private enum Kind {
        ACCESS("restofy-staff-api", "access"),
        MFA_PENDING("restofy-auth-mfa", "mfa_pending"),
        PASSWORD_PENDING("restofy-auth-password", "password_change");

        private final String audience;
        private final String tokenType;

        Kind(String audience, String tokenType) {
            this.audience = audience;
            this.tokenType = tokenType;
        }

        static Kind of(SessionAuthState state) {
            return switch (state) {
                case AUTHENTICATED -> ACCESS;
                case MFA_PENDING -> MFA_PENDING;
                case PASSWORD_PENDING -> PASSWORD_PENDING;
            };
        }
    }

    public record Identity(UUID userId, UUID sessionId, StaffRole role, SessionAuthState state) {
    }
}
