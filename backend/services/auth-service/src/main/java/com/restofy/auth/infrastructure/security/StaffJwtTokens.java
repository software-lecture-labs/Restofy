package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
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
        boolean pending = session.getAuthState() == SessionAuthState.MFA_PENDING;
        Instant expiry = now.plusSeconds(lifetimeSeconds(pending));
        if (expiry.isAfter(session.getAbsoluteExpiresAt())) {
            expiry = session.getAbsoluteExpiresAt();
        }
        return Jwts.builder().subject(session.getStaffUserId().toString()).issuer(settings.issuer())
                .audience().add(pending ? "restofy-auth-mfa" : "restofy-staff-api").and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString()).claim("sid", session.getId().toString())
                .claim("role", session.getRoleSnapshot().name()).claim("auth_state", session.getAuthState().name())
                .claim("restaurant_id", settings.restaurantId().toString())
                .claim("token_type", pending ? "mfa_pending" : "access")
                .signWith(settings.privateKey(), Jwts.SIG.RS256).compact();
    }

    private long lifetimeSeconds(boolean pending) {
        return pending ? SessionPolicy.MFA_PENDING_TTL.toSeconds() : settings.accessTtl();
    }

    public Identity verify(String token, boolean pending) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException();
            }
            var claims = Jwts.parser().verifyWith(settings.publicKey())
                    .sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant())).requireIssuer(settings.issuer())
                    .requireAudience(pending ? "restofy-auth-mfa" : "restofy-staff-api")
                    .require("token_type", pending ? "mfa_pending" : "access")
                    .require("restaurant_id", settings.restaurantId().toString())
                    .require("auth_state", pending ? "MFA_PENDING" : "AUTHENTICATED")
                    .build().parseSignedClaims(token).getPayload();
            Instant now = clock.instant();
            if (claims.getExpiration() == null || claims.getIssuedAt() == null || claims.getNotBefore() == null
                    || claims.getId() == null || !now.isBefore(claims.getExpiration().toInstant())
                    || claims.getIssuedAt().toInstant().isAfter(now)
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant()
                            .isAfter(claims.getIssuedAt().toInstant().plusSeconds(lifetimeSeconds(pending)))) {
                throw new IllegalArgumentException();
            }
            StaffRole role = StaffRole.valueOf(claims.get("role", String.class));
            if (pending && role != StaffRole.ADMIN) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(claims.getId());
            return new Identity(UUID.fromString(claims.getSubject()), UUID.fromString(claims.get("sid", String.class)),
                    role, pending ? SessionAuthState.MFA_PENDING : SessionAuthState.AUTHENTICATED);
        } catch (RuntimeException _) {
            throw new BadCredentialsException("Invalid session");
        }
    }

    public record Identity(UUID userId, UUID sessionId, StaffRole role, SessionAuthState state) {
    }
}
