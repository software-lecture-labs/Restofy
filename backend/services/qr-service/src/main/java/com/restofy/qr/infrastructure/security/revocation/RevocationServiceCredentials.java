package com.restofy.qr.infrastructure.security.revocation;

import java.time.Clock;
import java.util.UUID;

import io.jsonwebtoken.Jwts;
import com.restofy.qr.infrastructure.security.JwtDates;
import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Dedicated Auth-to-QR trust, disjoint from browser staff credentials. Missing configuration stays closed. */
@Component
public class RevocationServiceCredentials {
    private static final String BEARER = "Bearer ";
    private static final String AUTH_ISSUER = "auth-service";
    private static final String REVOCATION_AUDIENCE = "restofy-qr-revocation";
    private static final String SCOPE = "scope";
    private static final String SERVICE = "service";
    private static final String TOKEN_TYPE = "token_type";
    private static final String RESTAURANT_ID = "restaurant_id";
    private static final String GENERATION = "generation";
    private static final String MANIFEST = "manifest";

    private final Q5SecuritySettings settings;
    private final Clock clock;

    public RevocationServiceCredentials(Q5SecuritySettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public void verify(String authorization, StaffRevocationStore.Command command) {
        try {
            if (authorization == null || !authorization.startsWith(BEARER) || authorization.length() > 4103) throw new IllegalArgumentException();
            UUID restaurant = settings.restaurant();
            if (!restaurant.equals(command.restaurantId())) throw new IllegalArgumentException();
            var key = settings.commandKey();
            var claims = Jwts.parser().verifyWith(key).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> JwtDates.from(clock.instant())).requireIssuer(AUTH_ISSUER)
                    .requireAudience(REVOCATION_AUDIENCE).requireSubject(command.operationId().toString())
                    .require(TOKEN_TYPE, SERVICE).require(SCOPE, "staff:revocation:write")
                    .require(RESTAURANT_ID, restaurant.toString()).require(GENERATION, command.generation().toString())
                    .require(MANIFEST, StaffRevocationStore.digest(command)).build()
                    .parseSignedClaims(authorization.substring(BEARER.length())).getPayload();
            if (claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null
                    || claims.getId() == null || claims.getIssuedAt().toInstant().isAfter(clock.instant())
                    || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(30))) throw new IllegalArgumentException();
            UUID.fromString(claims.getId());
        } catch (Exception _) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid service credential");
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
            if (header==null || !header.startsWith(BEARER) || header.length()>4103) throw new IllegalArgumentException();
            var key = settings.commandKey();
            var claims=Jwts.parser().verifyWith(key).sig().clear().add(Jwts.SIG.RS256).and().clock(() -> JwtDates.from(clock.instant()))
                    .requireIssuer(AUTH_ISSUER).requireAudience(REVOCATION_AUDIENCE).require(TOKEN_TYPE,SERVICE)
                    .require(SCOPE,scope).require(RESTAURANT_ID,settings.restaurant().toString())
                    .build().parseSignedClaims(header.substring(BEARER.length())).getPayload();
            if (claims.getIssuedAt()==null || claims.getNotBefore()==null || claims.getExpiration()==null || claims.getId()==null
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant()) || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(30))) throw new IllegalArgumentException();
            UUID.fromString(claims.getSubject()); UUID.fromString(claims.getId()); UUID.fromString(claims.get(GENERATION,String.class));
        } catch (Exception _) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid service credential"); }
    }

    public String receipt(StaffRevocationStore.Command command, StaffRevocationStore.Receipt receipt) {
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Cannot sign uncommitted QR acknowledgement");
            }
            if (!command.operationId().equals(receipt.operationId()) || !command.generation().equals(receipt.generation())
                    || !StaffRevocationStore.digest(command).equals(receipt.digest())) throw new IllegalArgumentException();
            var key = settings.receiptKey();
            var now = clock.instant();
            return Jwts.builder().issuer("qr-service").subject(command.operationId().toString())
                    .audience().add("restofy-auth").and().issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now))
                    .expiration(JwtDates.from(now.plusSeconds(30))).id(UUID.randomUUID().toString())
                    .claim(TOKEN_TYPE, SERVICE).claim(SCOPE, "staff:revocation:ack")
                    .claim(RESTAURANT_ID, command.restaurantId().toString()).claim(GENERATION, command.generation().toString())
                    .claim(MANIFEST, receipt.digest()).signWith(key, Jwts.SIG.RS256).compact();
        } catch (Exception _) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Committed QR acknowledgement unavailable");
        }
    }

    public void verifyBootstrap(String authorization, UUID id, UUID restaurant, UUID generation, String scope, String manifest, UUID nonce) {
        try {
            if (authorization == null || !authorization.startsWith(BEARER) || authorization.length() > 4103
                    || !settings.restaurant().equals(restaurant)) throw new IllegalArgumentException();
            var key = settings.commandKey();
            var parser = Jwts.parser().verifyWith(key).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> JwtDates.from(clock.instant())).requireIssuer(AUTH_ISSUER).requireAudience(REVOCATION_AUDIENCE)
                    .requireSubject(id.toString()).require(TOKEN_TYPE, SERVICE).require(SCOPE, scope)
                    .require(RESTAURANT_ID, restaurant.toString()).require(GENERATION, generation.toString()).require(MANIFEST, manifest);
            if (nonce != null) parser.require("nonce", nonce.toString());
            var claims = parser.build().parseSignedClaims(authorization.substring(BEARER.length())).getPayload();
            if (claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null || claims.getId() == null
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant()) || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(30))) throw new IllegalArgumentException();
            UUID.fromString(claims.getId());
        } catch (Exception _) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid service credential"); }
    }

    public String baselineReceipt(StaffRevocationStore.Baseline baseline, StaffRevocationStore.Receipt receipt) {
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    || !baseline.operationId().equals(receipt.operationId()) || !StaffRevocationStore.baselineDigest(baseline).equals(receipt.digest())) throw new IllegalArgumentException();
            var key = settings.receiptKey();
            var now = clock.instant();
            return Jwts.builder().issuer("qr-service").subject(baseline.operationId().toString()).audience().add("restofy-auth").and()
                    .issuedAt(JwtDates.from(now)).notBefore(JwtDates.from(now)).expiration(JwtDates.from(now.plusSeconds(30))).id(UUID.randomUUID().toString())
                    .claim(TOKEN_TYPE, SERVICE).claim(SCOPE, "staff:bootstrap:ack").claim(RESTAURANT_ID, baseline.restaurantId().toString())
                    .claim(GENERATION, baseline.generation().toString()).claim("nonce", baseline.nonce().toString())
                    .claim(MANIFEST, receipt.digest()).signWith(key, Jwts.SIG.RS256).compact();
        } catch (Exception _) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Committed baseline acknowledgement unavailable"); }
    }
}
