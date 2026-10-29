package com.restofy.mail.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import com.restofy.mail.infrastructure.config.MailSettings;
import io.jsonwebtoken.Jwts;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

@Component
@PropertySource("classpath:mail-service-contract.properties")
public class MailServiceTokens {
    /** Upper bound on the token text we are willing to parse, rejected before any signature work. */
    static final int MAX_TOKEN_LENGTH = 4096;
    private final MailSettings settings;
    private final Environment env;
    private final Clock clock;

    public MailServiceTokens(MailSettings settings, Environment env, Clock clock) {
        this.settings = settings;
        this.env = env;
        this.clock = clock;
    }

    public UUID verify(String token) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException();
            }
            var claims = Jwts.parser().verifyWith(settings.key()).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant())).requireIssuer(settings.issuer())
                    .requireSubject(contract("subject")).requireAudience(contract("audience"))
                    .require("token_type", contract("token-type")).require("scope", contract("scope"))
                    .build().parseSignedClaims(token).getPayload();
            Instant now = clock.instant();
            if (claims.getIssuedAt() == null || claims.getExpiration() == null || claims.getNotBefore() == null
                    || !Set.of(contract("audience")).equals(claims.getAudience())
                    || !claims.getNotBefore().equals(claims.getIssuedAt())
                    || claims.getIssuedAt().toInstant().isAfter(now) || !now.isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().after(claims.getIssuedAt())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(
                            Long.parseLong(contract("ttl-seconds"))))) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(claims.getId());
        } catch (RuntimeException _) {
            throw new BadCredentialsException("Invalid service identity");
        }
    }

    private String contract(String key) {
        return env.getRequiredProperty("restofy.mail-contract." + key);
    }
}
