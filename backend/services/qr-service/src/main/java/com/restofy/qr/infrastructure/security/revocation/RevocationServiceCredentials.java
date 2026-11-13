package com.restofy.qr.infrastructure.security.revocation;

import java.time.Clock;
import java.util.Date;
import java.util.UUID;

import io.jsonwebtoken.Jwts;
import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Dedicated Auth-to-QR trust, disjoint from browser staff credentials. Missing configuration stays closed. */
@Component
public class RevocationServiceCredentials {
    private final Q5SecuritySettings settings;
    private final Clock clock;

    public RevocationServiceCredentials(Q5SecuritySettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public void verify(String authorization, StaffRevocationStore.Command command) {
        try {
            if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 4103) throw new IllegalArgumentException();
            UUID restaurant = settings.restaurant();
            if (!restaurant.equals(command.restaurantId())) throw new IllegalArgumentException();
            var key = settings.commandKey();
            var claims = Jwts.parser().verifyWith(key).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant())).requireIssuer("auth-service")
                    .requireAudience("restofy-qr-revocation").requireSubject(command.operationId().toString())
                    .require("token_type", "service").require("scope", "staff:revocation:write")
                    .require("restaurant_id", restaurant.toString()).require("generation", command.generation().toString())
                    .require("manifest", StaffRevocationStore.digest(command)).build()
                    .parseSignedClaims(authorization.substring(7)).getPayload();
            if (claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null
                    || claims.getId() == null || claims.getIssuedAt().toInstant().isAfter(clock.instant())
                    || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(30))) throw new IllegalArgumentException();
            UUID.fromString(claims.getId());
        } catch (Exception failure) {
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
            if (header==null || !header.startsWith("Bearer ") || header.length()>4103) throw new IllegalArgumentException();
            var key = settings.commandKey();
            var claims=Jwts.parser().verifyWith(key).sig().clear().add(Jwts.SIG.RS256).and().clock(() -> Date.from(clock.instant()))
                    .requireIssuer("auth-service").requireAudience("restofy-qr-revocation").require("token_type","service")
                    .require("scope",scope).require("restaurant_id",settings.restaurant().toString())
                    .build().parseSignedClaims(header.substring(7)).getPayload();
            if (claims.getIssuedAt()==null || claims.getNotBefore()==null || claims.getExpiration()==null || claims.getId()==null
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant()) || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(30))) throw new IllegalArgumentException();
            UUID.fromString(claims.getSubject()); UUID.fromString(claims.getId()); UUID.fromString(claims.get("generation",String.class));
        } catch (Exception failure) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid service credential"); }
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
                    .audience().add("restofy-auth").and().issuedAt(Date.from(now)).notBefore(Date.from(now))
                    .expiration(Date.from(now.plusSeconds(30))).id(UUID.randomUUID().toString())
                    .claim("token_type", "service").claim("scope", "staff:revocation:ack")
                    .claim("restaurant_id", command.restaurantId().toString()).claim("generation", command.generation().toString())
                    .claim("manifest", receipt.digest()).signWith(key, Jwts.SIG.RS256).compact();
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Committed QR acknowledgement unavailable");
        }
    }

    public void verifyBootstrap(String authorization, UUID id, UUID restaurant, UUID generation, String scope, String manifest, UUID nonce) {
        try {
            if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 4103
                    || !settings.restaurant().equals(restaurant)) throw new IllegalArgumentException();
            var key = settings.commandKey();
            var parser = Jwts.parser().verifyWith(key).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant())).requireIssuer("auth-service").requireAudience("restofy-qr-revocation")
                    .requireSubject(id.toString()).require("token_type", "service").require("scope", scope)
                    .require("restaurant_id", restaurant.toString()).require("generation", generation.toString()).require("manifest", manifest);
            if (nonce != null) parser.require("nonce", nonce.toString());
            var claims = parser.build().parseSignedClaims(authorization.substring(7)).getPayload();
            if (claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null || claims.getId() == null
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant()) || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(30))) throw new IllegalArgumentException();
            UUID.fromString(claims.getId());
        } catch (Exception failure) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid service credential"); }
    }

    public String baselineReceipt(StaffRevocationStore.Baseline baseline, StaffRevocationStore.Receipt receipt) {
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    || !baseline.operationId().equals(receipt.operationId()) || !StaffRevocationStore.baselineDigest(baseline).equals(receipt.digest())) throw new IllegalArgumentException();
            var key = settings.receiptKey();
            var now = clock.instant();
            return Jwts.builder().issuer("qr-service").subject(baseline.operationId().toString()).audience().add("restofy-auth").and()
                    .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(30))).id(UUID.randomUUID().toString())
                    .claim("token_type", "service").claim("scope", "staff:bootstrap:ack").claim("restaurant_id", baseline.restaurantId().toString())
                    .claim("generation", baseline.generation().toString()).claim("nonce", baseline.nonce().toString())
                    .claim("manifest", receipt.digest()).signWith(key, Jwts.SIG.RS256).compact();
        } catch (Exception failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Committed baseline acknowledgement unavailable"); }
    }
}
