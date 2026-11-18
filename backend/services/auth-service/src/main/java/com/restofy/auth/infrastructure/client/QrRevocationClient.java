package com.restofy.auth.infrastructure.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import com.restofy.auth.application.service.revocation.RevocationTransactions.Operation;
import com.restofy.auth.infrastructure.security.QrRevocationReceipts;
import io.jsonwebtoken.Jwts;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/** Dedicated service credential; never sends a staff JWT or any browser cookie. */
@Component
public class QrRevocationClient implements AutoCloseable {
    private final Clock clock;
    private final RSAPrivateCrtKey commandKey;
    private final UUID generation;
    private final String origin;
    private final HttpClient http;

    public QrRevocationClient(Environment environment, Clock clock) {
        this.clock = clock;
        try {
            origin = origin(environment);
            generation = UUID.fromString(environment.getRequiredProperty("auth.q5.qr-generation"));
            commandKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(environment.getRequiredProperty("auth.q5.command-private-key"))));
            var publicKey=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(environment.getRequiredProperty("auth.q5.command-public-key"))));
            if (commandKey.getModulus().bitLength() < 2048 || !commandKey.getModulus().equals(publicKey.getModulus())
                    || !commandKey.getPublicExponent().equals(publicKey.getPublicExponent())) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            // No cause/value: configuration parsing exceptions can contain sensitive input.
            throw new IllegalStateException("Invalid Q5 command key, QR generation or internal QR URL");
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public UUID generation() { return generation; }

    public String persist(Operation operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("HTTP inside transaction");
        try {
            var now = clock.instant();
            String credential = Jwts.builder().issuer("auth-service").subject(operation.id().toString())
                    .audience().add("restofy-qr-revocation").and().issuedAt(Date.from(now)).notBefore(Date.from(now))
                    .expiration(Date.from(now.plusSeconds(30))).id(UUID.randomUUID().toString())
                    .claim("token_type", "service").claim("scope", "staff:revocation:write")
                    .claim("restaurant_id", operation.restaurantId().toString())
                    .claim("generation", operation.consumerGeneration().toString())
                    .claim("manifest", QrRevocationReceipts.manifestDigest(operation)).signWith(commandKey, Jwts.SIG.RS256).compact();
            var factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofSeconds(3));
            Ack ack = RestClient.builder().requestFactory(factory).baseUrl(origin).build().post()
                    .uri("/internal/q5/revocations").header("Authorization", "Bearer " + credential)
                    .body(new Command(operation.id(), operation.restaurantId(), operation.userId(), operation.revision(),
                            operation.consumerGeneration(), operation.targets())).retrieve().body(Ack.class);
            if (ack == null || ack.receipt() == null || ack.receipt().length() > 4096) throw new IllegalArgumentException();
            return ack.receipt();
        } catch (Exception failure) {
            // Do not attach transport exceptions which can contain credentials or response data.
            throw new IllegalStateException("QR durable acknowledgement unavailable");
        }
    }

    private record Command(UUID operationId, UUID restaurantId, UUID userId, long revision, UUID generation,
            java.util.List<com.restofy.auth.application.service.revocation.RevocationTransactions.Target> targets) { }
    private record Ack(String receipt) {
        @Override public String toString() { return "Q5 durable acknowledgement"; }
    }

    public Challenge challenge(UUID restaurant, UUID generation) {
        UUID id = UUID.randomUUID();
        return bootstrapPost("/internal/q5/challenge", new ChallengeRequest(id, restaurant, generation), id,
                restaurant, generation, "staff:bootstrap:challenge", "challenge", null, Challenge.class);
    }

    public String baseline(com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions.Baseline baseline) {
        Ack ack = bootstrapPost("/internal/q5/bootstrap", baseline, baseline.operationId(), baseline.restaurantId(), baseline.generation(),
                "staff:bootstrap:write", com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions.digest(baseline), baseline.nonce(), Ack.class);
        if (ack == null || ack.receipt() == null || ack.receipt().length() > 4096) throw new IllegalStateException("Missing baseline receipt");
        return ack.receipt();
    }

    private <T> T bootstrapPost(String path, Object body, UUID id, UUID restaurant, UUID generation, String scope, String manifest,
            UUID nonce, Class<T> response) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("HTTP inside transaction");
        try {
            var now = clock.instant();
            var builder = Jwts.builder().issuer("auth-service").subject(id.toString()).audience().add("restofy-qr-revocation").and()
                    .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(30)))
                    .id(UUID.randomUUID().toString()).claim("token_type", "service").claim("scope", scope)
                    .claim("restaurant_id", restaurant.toString()).claim("generation", generation.toString()).claim("manifest", manifest);
            if (nonce != null) builder.claim("nonce", nonce.toString());
            var factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofSeconds(3));
            return RestClient.builder().requestFactory(factory).baseUrl(origin).build().post().uri(path)
                    .header("Authorization", "Bearer " + builder.signWith(commandKey, Jwts.SIG.RS256).compact()).body(body).retrieve().body(response);
        } catch (Exception failure) { throw new IllegalStateException("QR bootstrap unavailable"); }
    }

    public record Challenge(UUID nonce, boolean ready) { }
    private record ChallengeRequest(UUID operationId, UUID restaurantId, UUID generation) { }

    private static String origin(Environment environment) {
        URI target=URI.create(environment.getRequiredProperty("auth.q5.qr-url"));
        boolean development=com.restofy.auth.infrastructure.config.EnvironmentPolicy.isDevelopment(environment)
                && com.restofy.auth.infrastructure.config.EnvironmentPolicy.isDevelopmentEnvironmentName(environment.getProperty("auth.bootstrap.environment",""));
        boolean local=target.getHost()!=null && java.util.Set.of("127.0.0.1","localhost","qr-service").contains(target.getHost());
        if (target.getHost()==null || (!"https".equals(target.getScheme()) && !(development && local && "http".equals(target.getScheme())))
                || (target.getPort()!=-1 && (target.getPort()<1 || target.getPort()>65535))
                || target.getUserInfo()!=null || target.getQuery()!=null || target.getFragment()!=null
                || !(target.getPath().isEmpty() || target.getPath().equals("/"))) throw new IllegalArgumentException("Invalid internal QR origin");
        return target.getScheme()+"://"+target.getRawAuthority();
    }

    @Override @jakarta.annotation.PreDestroy
    public void close() { http.close(); }
}
