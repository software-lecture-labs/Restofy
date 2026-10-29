package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.infrastructure.config.NotificationTrustSettings;
import io.jsonwebtoken.Jwts;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

@Component
public class NotificationServiceTokens {
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final long MAX_LIFETIME_SECONDS = 60;
    private static final String CALLER = "notification-service";
    private final NotificationTrustSettings trust;
    private final AuthSettings settings;
    private final Clock clock;

    public NotificationServiceTokens(NotificationTrustSettings trust, AuthSettings settings, Clock clock) {
        this.trust = trust;
        this.settings = settings;
        this.clock = clock;
        if (trust.publicKey() != null && trust.publicKey().equals(settings.publicKey())) {
            throw new IllegalStateException("AUTH_NOTIFICATION_PUBLIC_KEY must be separate from the Auth signing key");
        }
    }

    public void verify(String authorization) {
        try {
            if (trust.publicKey() == null || authorization == null || !authorization.startsWith("Service ")
                    || authorization.length() > MAX_TOKEN_LENGTH + "Service ".length()) {
                throw new IllegalArgumentException();
            }
            var claims = Jwts.parser().verifyWith(trust.publicKey()).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant())).requireIssuer(CALLER).requireSubject(CALLER)
                    .requireAudience("restofy-auth").require("token_type", "service")
                    .require("scope", "staff:recipients:read")
                    .require("restaurant_id", settings.restaurantId().toString())
                    .build().parseSignedClaims(authorization.substring("Service ".length())).getPayload();
            Instant now = clock.instant();
            if (claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null
                    || claims.getId() == null || claims.getIssuedAt().toInstant().isAfter(now)
                    || !now.isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant()
                            .isAfter(claims.getIssuedAt().toInstant().plusSeconds(MAX_LIFETIME_SECONDS))) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(claims.getId());
        } catch (RuntimeException _) {
            throw new BadCredentialsException("Invalid service credential");
        }
    }
}
