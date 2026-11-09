package com.restofy.auth.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class AuthSessionTestSupport {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate q5FixtureDatabase;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.restofy.auth.application.service.revocation.RevocationTransactions q5FixtureTransactions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private java.time.Clock q5FixtureClock;

    /** Auth-only peer signature fixture, not evidence of QR commit. QR commit is tested in QR's PostgreSQL HTTP tests. */
    protected void acknowledgePreparedRevocationsForAuthFixture() {
        for (var id:q5FixtureDatabase.queryForList("SELECT id FROM staff_revocation_operations WHERE status='PREPARED'",java.util.UUID.class)) {
            var operation=q5FixtureTransactions.load(id);
            var now=q5FixtureClock.instant();
            String receipt=io.jsonwebtoken.Jwts.builder().issuer("qr-service").subject(id.toString()).audience().add("restofy-auth").and()
                    .issuedAt(java.util.Date.from(now)).notBefore(java.util.Date.from(now)).expiration(java.util.Date.from(now.plusSeconds(30)))
                    .id(java.util.UUID.randomUUID().toString()).claim("token_type","service").claim("scope","staff:revocation:ack")
                    .claim("restaurant_id",operation.restaurantId().toString()).claim("generation",operation.consumerGeneration().toString())
                    .claim("manifest",com.restofy.auth.infrastructure.security.QrRevocationReceipts.manifestDigest(operation))
                    .signWith(Q5_QR_KEYS.getPrivate(),io.jsonwebtoken.Jwts.SIG.RS256).compact();
            q5FixtureTransactions.recordAcknowledgement(id,receipt);
            q5FixtureTransactions.finalizeOperation(id);
        }
    }

    protected void coordinatedAuthFixture(Runnable action) {
        coordinatedAuthResult(() -> { action.run(); return Boolean.TRUE; });
    }

    protected void assertCoordinatedLogoutHttp(org.springframework.test.web.servlet.MockMvc mvc,
            java.util.function.Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> request) throws Exception {
        var result=mvc.perform(request.get())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .filteredOn(value -> value.contains("Max-Age=0"))
                .anyMatch(value -> value.startsWith("restofy_access="))
                .anyMatch(value -> value.startsWith("restofy_refresh="))
                .anyMatch(value -> value.startsWith("restofy_mfa="))
                .anyMatch(value -> value.startsWith("restofy_csrf="));
    }

    protected <T> T coordinatedAuthResult(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (org.springframework.web.server.ResponseStatusException pending) {
            org.assertj.core.api.Assertions.assertThat(pending.getStatusCode().value()).isEqualTo(503);
            var operations=q5FixtureDatabase.queryForList("SELECT id FROM staff_revocation_operations WHERE status='PREPARED'",java.util.UUID.class);
            org.assertj.core.api.Assertions.assertThat(operations).isNotEmpty();
            acknowledgePreparedRevocationsForAuthFixture();
            return null;
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void clearQ5OperationsBeforeExistingFixturesDeleteTheirSessions() {
        if (q5FixtureDatabase != null) {
            // Explicit fixture baseline; production starts CLOSED and requires a signed QR baseline ACK.
            q5FixtureDatabase.update("UPDATE staff_revocation_source_state SET phase='ACTIVE',database_start=pg_postmaster_start_time(),bootstrap_id=?,qr_nonce=?,restaurant_id=?,generation=?,manifest_digest=? WHERE singleton=true",
                    java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.fromString("6c54daf0-9340-4a1c-981d-f9acc0261779"),Q5_GENERATION,"0".repeat(64));
            q5FixtureDatabase.update("DELETE FROM staff_revocation_targets");
            q5FixtureDatabase.update("DELETE FROM staff_revocation_operations");
        }
    }

    private static final KeyPair KEYS = generateKeys();
    public static final KeyPair Q5_QR_KEYS = generateKeys();
    public static final KeyPair Q5_AUTH_KEYS = generateKeys();
    public static final java.util.UUID Q5_GENERATION = java.util.UUID.randomUUID();

    public static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void sessionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:tc:q5postgresql:17-alpine:///restofy_test");
        registry.add("auth.q5.qr-public-key", () -> Base64.getEncoder().encodeToString(Q5_QR_KEYS.getPublic().getEncoded()));
        registry.add("auth.q5.qr-generation", () -> Q5_GENERATION.toString());
        registry.add("auth.q5.retry.enabled", () -> "false");
        registry.add("auth.q5.qr-url", () -> "http://127.0.0.1:9");
        registry.add("auth.q5.command-private-key", () -> Base64.getEncoder().encodeToString(Q5_AUTH_KEYS.getPrivate().getEncoded()));
        registry.add("auth.q5.command-public-key", () -> Base64.getEncoder().encodeToString(Q5_AUTH_KEYS.getPublic().getEncoded()));
        registry.add("auth.reset.hmac-key", () -> Base64.getEncoder().encodeToString(
                "0123456789abcdefghijklmnopqrstuv".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        registry.add("auth.mfa.encryption-key", () -> Base64.getEncoder().encodeToString(
                "0123456789abcdefghijklmnopqrstuv".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        registry.add("auth.session.restaurant-id", () -> "6c54daf0-9340-4a1c-981d-f9acc0261779");
        registry.add("auth.session.private-key", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        registry.add("auth.session.csrf-secret", () -> Base64.getEncoder().encodeToString(
                java.util.stream.IntStream.range(1, 33).collect(java.io.ByteArrayOutputStream::new,
                        java.io.ByteArrayOutputStream::write, (left, right) -> left.writeBytes(right.toByteArray())).toByteArray()));
        registry.add("auth.session.cookie-secure", () -> "false");
        registry.add("auth.session.issuer", () -> "restofy-auth");
        registry.add("auth.session.access-ttl-seconds", () -> "300");
        registry.add("auth.session.refresh-ttl-seconds", () -> "604800");
        registry.add("auth.session.allowed-origins", () -> "http://localhost:5000");
    }
}
