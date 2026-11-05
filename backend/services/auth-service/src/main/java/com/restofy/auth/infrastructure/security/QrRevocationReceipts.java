package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

import com.restofy.auth.application.service.revocation.RevocationTransactions.Operation;
import io.jsonwebtoken.Jwts;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** A transport response alone is never proof that the configured QR authority acknowledged this manifest. */
@Component
public class QrRevocationReceipts {
    private final RSAPublicKey publicKey;
    private final Clock clock;

    public QrRevocationReceipts(Environment environment, Clock clock) {
        this.clock = clock;
        try {
            publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(environment.getRequiredProperty("auth.q5.qr-public-key"))));
            if (publicKey.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
        } catch (Exception failure) {
            throw new IllegalStateException("Missing or invalid AUTH_Q5_QR_PUBLIC_KEY (Base64 X.509 RSA, 2048+ bits)");
        }
    }

    public void verify(String receipt, Operation operation) {
        try {
            if (receipt == null || receipt.length() > 4096) throw new IllegalArgumentException();
            var claims = Jwts.parser().verifyWith(publicKey).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant())).requireIssuer("qr-service")
                    .requireAudience("restofy-auth").requireSubject(operation.id().toString())
                    .require("scope", "staff:revocation:ack").require("token_type", "service")
                    .require("restaurant_id", operation.restaurantId().toString())
                    .require("generation", operation.consumerGeneration().toString())
                    .require("manifest", manifestDigest(operation)).build().parseSignedClaims(receipt).getPayload();
            if (claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null
                    || claims.getId() == null || claims.getIssuedAt().toInstant().isAfter(clock.instant())
                    || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(60))) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(claims.getId());
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "QR acknowledgement is not verifiable");
        }
    }

    public static String manifestDigest(Operation operation) {
        String targets = operation.targets().stream().sorted(java.util.Comparator.comparing(target -> target.sessionId().toString()))
                .map(target -> target.sessionId() + ":" + target.denyUntil()).collect(Collectors.joining("\n"));
        String text = operation.id() + "\n" + operation.restaurantId() + "\n" + operation.userId() + "\n"
                + operation.revision() + "\n" + operation.consumerGeneration() + "\n" + targets;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    public void verifyBaseline(String receipt, com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions.Baseline baseline) {
        try {
            if (receipt == null || receipt.length() > 4096) throw new IllegalArgumentException();
            var claims = Jwts.parser().verifyWith(publicKey).sig().clear().add(Jwts.SIG.RS256).and()
                    .clock(() -> Date.from(clock.instant())).requireIssuer("qr-service").requireAudience("restofy-auth")
                    .requireSubject(baseline.operationId().toString()).require("token_type", "service")
                    .require("scope", "staff:bootstrap:ack").require("nonce", baseline.nonce().toString())
                    .require("restaurant_id", baseline.restaurantId().toString()).require("generation", baseline.generation().toString())
                    .require("manifest", com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions.digest(baseline))
                    .build().parseSignedClaims(receipt).getPayload();
            if (claims.getIssuedAt() == null || claims.getNotBefore() == null || claims.getExpiration() == null || claims.getId() == null
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant()) || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(30))) throw new IllegalArgumentException();
            UUID.fromString(claims.getId());
        } catch (RuntimeException failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "QR baseline acknowledgement is not verifiable"); }
    }
}
