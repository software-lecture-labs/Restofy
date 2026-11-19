package com.restofy.qr.infrastructure.security.revocation;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Dedicated Auth-to-QR trust, disjoint from browser staff credentials. Missing configuration stays closed. */
@Component
public class RevocationServiceCredentials {
    private static final String BEARER = "Bearer ";
    private static final int MAX_BEARER_LENGTH = 4103;
    private static final String AUTH_ISSUER = "auth-service";
    private static final String AUDIENCE = "restofy-qr-revocation";
    private static final String TOKEN_TYPE = "token_type";
    private static final String SERVICE = "service";
    private static final String SCOPE = "scope";
    private static final String RESTAURANT_ID = "restaurant_id";
    private static final String GENERATION = "generation";
    private static final String MANIFEST = "manifest";
    private static final String INVALID_CREDENTIAL = "Invalid service credential";
    private static final long MAX_LIFETIME_SECONDS = 30;

    private final Q5SecuritySettings settings;
    private final Clock clock;

    public RevocationServiceCredentials(Q5SecuritySettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public void verify(String authorization, StaffRevocationStore.Command command) {
        try {
            if (!bearer(authorization)) throw new IllegalArgumentException();
            UUID restaurant = settings.restaurant();
            if (!restaurant.equals(command.restaurantId())) throw new IllegalArgumentException();
            var claims = Jwts.parser().verifyWith(settings.commandKey()).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> java.util.Date.from(clock.instant())).requireIssuer(AUTH_ISSUER)
                    .requireAudience(AUDIENCE).requireSubject(command.operationId().toString())
                    .require(TOKEN_TYPE, SERVICE).require(SCOPE, "staff:revocation:write")
                    .require(RESTAURANT_ID, restaurant.toString()).require(GENERATION, command.generation().toString())
                    .require(MANIFEST, StaffRevocationStore.digest(command)).build()
                    .parseSignedClaims(authorization.substring(BEARER.length())).getPayload();
            requireFresh(claims);
            UUID.fromString(claims.getId());
        } catch (Exception _) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIAL);
        }
    }

    /** Authenticate before MVC allocates/deserializes an internal command body. Binding is checked again afterwards. */
    public void verifyCaller(jakarta.servlet.http.HttpServletRequest request) {
        try {
            if (request.getHeader("Cookie")!=null || request.getHeader("Origin")!=null
                    || java.util.Collections.list(request.getHeaders("Authorization")).size()!=1) throw new IllegalArgumentException();
            String scope=switch(request.getRequestURI()) {
                case "/internal/q5/revocations" -> "staff:revocation:write";
                case "/internal/q5/challenge" -> "staff:bootstrap:challenge";
                case "/internal/q5/bootstrap" -> "staff:bootstrap:write";
                default -> throw new IllegalArgumentException();
            };
            String header=request.getHeader("Authorization");
            if (!bearer(header)) throw new IllegalArgumentException();
            var claims=Jwts.parser().verifyWith(settings.commandKey()).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> java.util.Date.from(clock.instant()))
                    .requireIssuer(AUTH_ISSUER).requireAudience(AUDIENCE).require(TOKEN_TYPE,SERVICE)
                    .require(SCOPE,scope).require(RESTAURANT_ID,settings.restaurant().toString())
                    .build().parseSignedClaims(header.substring(BEARER.length())).getPayload();
            requireFresh(claims);
            UUID.fromString(claims.getSubject()); UUID.fromString(claims.get(GENERATION,String.class));
        } catch (Exception _) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,INVALID_CREDENTIAL); }
    }

    public String receipt(StaffRevocationStore.Command command, StaffRevocationStore.Receipt receipt) {
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Cannot sign uncommitted QR acknowledgement");
            }
            if (!command.operationId().equals(receipt.operationId()) || !command.generation().equals(receipt.generation())
                    || !StaffRevocationStore.digest(command).equals(receipt.digest())) throw new IllegalArgumentException();
            var now = clock.instant();
            return Jwts.builder().issuer("qr-service").subject(command.operationId().toString())
                    .audience().add("restofy-auth").and().issuedAt(java.util.Date.from(now)).notBefore(java.util.Date.from(now))
                    .expiration(java.util.Date.from(now.plusSeconds(MAX_LIFETIME_SECONDS))).id(UUID.randomUUID().toString())
                    .claim(TOKEN_TYPE, SERVICE).claim(SCOPE, "staff:revocation:ack")
                    .claim(RESTAURANT_ID, command.restaurantId().toString()).claim(GENERATION, command.generation().toString())
                    .claim(MANIFEST, receipt.digest()).signWith(settings.receiptKey(), Jwts.SIG.RS256).compact();
        } catch (Exception _) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Committed QR acknowledgement unavailable");
        }
    }

    public void verifyBootstrap(String authorization, UUID id, UUID restaurant, UUID generation, String scope, String manifest, UUID nonce) {
        try {
            if (!bearer(authorization) || !settings.restaurant().equals(restaurant)) throw new IllegalArgumentException();
            var parser = Jwts.parser().verifyWith(settings.commandKey()).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> java.util.Date.from(clock.instant())).requireIssuer(AUTH_ISSUER).requireAudience(AUDIENCE)
                    .requireSubject(id.toString()).require(TOKEN_TYPE, SERVICE).require(SCOPE, scope)
                    .require(RESTAURANT_ID, restaurant.toString()).require(GENERATION, generation.toString()).require(MANIFEST, manifest);
            if (nonce != null) parser.require("nonce", nonce.toString());
            var claims = parser.build().parseSignedClaims(authorization.substring(BEARER.length())).getPayload();
            requireFresh(claims);
            UUID.fromString(claims.getId());
        } catch (Exception _) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIAL); }
    }

    public String baselineReceipt(StaffRevocationStore.Baseline baseline, StaffRevocationStore.Receipt receipt) {
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    || !baseline.operationId().equals(receipt.operationId()) || !StaffRevocationStore.baselineDigest(baseline).equals(receipt.digest())) throw new IllegalArgumentException();
            var now = clock.instant();
            return Jwts.builder().issuer("qr-service").subject(baseline.operationId().toString()).audience().add("restofy-auth").and()
                    .issuedAt(java.util.Date.from(now)).notBefore(java.util.Date.from(now)).expiration(java.util.Date.from(now.plusSeconds(MAX_LIFETIME_SECONDS)))
                    .id(UUID.randomUUID().toString())
                    .claim(TOKEN_TYPE, SERVICE).claim(SCOPE, "staff:bootstrap:ack").claim(RESTAURANT_ID, baseline.restaurantId().toString())
                    .claim(GENERATION, baseline.generation().toString()).claim("nonce", baseline.nonce().toString())
                    .claim(MANIFEST, receipt.digest()).signWith(settings.receiptKey(), Jwts.SIG.RS256).compact();
        } catch (Exception _) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Committed baseline acknowledgement unavailable"); }
    }

    private static boolean bearer(String authorization) {
        return authorization != null && authorization.startsWith(BEARER) && authorization.length() <= MAX_BEARER_LENGTH;
    }

    /** iat/nbf/exp/jti present, issued in the past, not expired, positive lifetime of at most 30 seconds. */
    private void requireFresh(Claims claims) {
        if (claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null || claims.getId() == null) {
            throw new IllegalArgumentException();
        }
        Instant now = clock.instant();
        Instant issued = claims.getIssuedAt().toInstant();
        Instant expires = claims.getExpiration().toInstant();
        if (issued.isAfter(now) || !now.isBefore(expires) || !expires.isAfter(issued)
                || expires.isAfter(issued.plusSeconds(MAX_LIFETIME_SECONDS))) throw new IllegalArgumentException();
        UUID.fromString(claims.getId());
    }
}
