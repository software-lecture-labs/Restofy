package com.restofy.auth.application.service;

import java.util.UUID;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.restofy.auth.application.service.revocation.RevocationTransactions;
import com.restofy.auth.application.service.revocation.RevocationTransactions.Action;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.infrastructure.security.QrRevocationReceipts;
import com.restofy.auth.infrastructure.security.StaffJwtTokens;
import io.jsonwebtoken.Jwts;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
class RevocationTransactionsTests extends AuthSessionTestSupport {
    @Autowired private RevocationTransactions revocations;
    @Autowired private StaffSessionService sessions;
    @Autowired private StaffUserRepository users;
    @Autowired private StaffPasswords passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private StaffJwtTokens jwt;
    @Autowired private com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions bootstrap;
    private UUID user;
    private final UUID restaurant = UUID.fromString("6c54daf0-9340-4a1c-981d-f9acc0261779");
    private final UUID generation = Q5_GENERATION;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM staff_revocation_targets");
        jdbc.update("DELETE FROM staff_revocation_operations");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM user_sessions");
        jdbc.update("DELETE FROM staff_users");
        user = users.saveAndFlush(new StaffUser("q5@example.com", passwords.hash("Q5-test-password-01"), StaffRole.WAITER)).getId();
    }

    @AfterEach
    void clearOperations() {
        jdbc.update("DELETE FROM staff_revocation_targets");
        jdbc.update("DELETE FROM staff_revocation_operations");
    }

    @Test
    void sourceIdentityMismatchClosesReadinessAndCannotCreateAnOperation() {
        UUID other = UUID.randomUUID();
        for (String column : java.util.List.of("generation", "restaurant_id")) {
            jdbc.update("UPDATE staff_revocation_source_state SET " + column + "=? WHERE singleton=true", other);
            assertThat(bootstrap.active()).isFalse();
            assertThatThrownBy(() -> revocations.prepare(UUID.randomUUID(),restaurant,user,Action.ALL,null,null,generation))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> sessions.login("q5@example.com","Q5-test-password-01",null))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations",Long.class)).isZero();
            jdbc.update("UPDATE staff_revocation_source_state SET generation=?,restaurant_id=? WHERE singleton=true",generation,restaurant);
        }
        assertThat(bootstrap.active()).isTrue();
        assertThat(sessions.login("q5@example.com","Q5-test-password-01",null)).isNotNull();
    }

    @Test
    void mismatchedCommandIdentityCannotPoisonTheSharedSource() {
        assertThatThrownBy(() -> revocations.prepare(UUID.randomUUID(),restaurant,user,Action.ALL,null,null,UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> revocations.prepare(UUID.randomUUID(),UUID.randomUUID(),user,Action.ALL,null,null,generation))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> bootstrap.prepare(restaurant,UUID.randomUUID(),UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations",Long.class)).isZero();
        assertThat(bootstrap.active()).isTrue();
    }

    @Test
    void receiptsLongerThanThirtySecondsCannotAcknowledgeOrFinalize() {
        UUID id = UUID.randomUUID();
        revocations.prepare(id,restaurant,user,Action.ALL,null,null,generation);
        assertThatThrownBy(() -> revocations.recordAcknowledgement(id,
                acknowledgement(id, builder -> builder.expiration(Date.from(Instant.now().plusSeconds(60))))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(revocations.load(id).status()).isEqualTo("PREPARED");
        assertThatThrownBy(() -> revocations.finalizeOperation(id)).isInstanceOf(ResponseStatusException.class);
        revocations.recordAcknowledgement(id,acknowledgement(id));
        revocations.finalizeOperation(id);
        assertThat(revocations.load(id).status()).isEqualTo("COMPLETED");
    }

    @Test
    void logoutMustNotFinalizeBeforeQrAcknowledgesItsDurableFence() {
        var grant = sessions.login("q5@example.com", "Q5-test-password-01", null);

        // No QR command or acknowledgement has occurred. A pending response is acceptable;
        // committing the local revocation before the QR fence is not.
        try {
            sessions.logout(grant.refresh(), grant.access(), null);
        } catch (ResponseStatusException pending) {
            assertThat(pending.getStatusCode().value()).isIn(409, 503);
        }

        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM user_sessions WHERE id=?",
                Boolean.class, grant.session().getId()))
                .as("Q5: logout cannot FINALIZE without QR's durable acknowledgement")
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations WHERE user_id=? AND status='PREPARED'",
                Long.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_targets WHERE session_id=?",
                Long.class, grant.session().getId())).isEqualTo(1);
    }

    @Test
    void logoutRejectsAnAmbientTransactionInsteadOfSuspendingItsLocksAcrossHttp() {
        var grant=sessions.login("q5@example.com","Q5-test-password-01",null);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThatThrownBy(() -> sessions.logout(grant.refresh(),grant.access(),null))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM user_sessions WHERE id=?",Boolean.class,grant.session().getId())).isTrue();
    }

    @Test
    void productionEnvironmentCannotInheritPlainHttpFromADevelopmentProfile() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/internal/q5/revocations",exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body="{\"receipt\":\"transport-fixture-not-an-authenticated-ack\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var environment=new org.springframework.mock.env.MockEnvironment()
                .withProperty("auth.q5.qr-url","http://127.0.0.1:"+server.getAddress().getPort()+"/")
                .withProperty("auth.q5.qr-generation",generation.toString())
                .withProperty("auth.q5.command-private-key",java.util.Base64.getEncoder().encodeToString(Q5_AUTH_KEYS.getPrivate().getEncoded()))
                .withProperty("auth.q5.command-public-key",java.util.Base64.getEncoder().encodeToString(Q5_AUTH_KEYS.getPublic().getEncoded()))
                .withProperty("auth.bootstrap.environment","prod");
        environment.setActiveProfiles("dev");
        var operation=revocations.prepare(UUID.randomUUID(),restaurant,user,Action.ALL,null,null,generation);
        try {
            assertThatThrownBy(() -> new com.restofy.auth.infrastructure.client.QrRevocationClient(environment,java.time.Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(calls.get()).isZero();
            environment.withProperty("auth.bootstrap.environment","dev"); environment.setActiveProfiles("prod");
            assertThatThrownBy(() -> new com.restofy.auth.infrastructure.client.QrRevocationClient(environment,java.time.Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(calls.get()).isZero();
            environment.setActiveProfiles("dev");
            try (var client=new com.restofy.auth.infrastructure.client.QrRevocationClient(environment,java.time.Clock.systemUTC())) {
                assertThat(client.persist(operation)).isEqualTo("transport-fixture-not-an-authenticated-ack");
            }
            assertThat(calls.get()).isEqualTo(1);
            assertThat(revocations.load(operation.id()).status()).isEqualTo("PREPARED");
        } finally { server.stop(0); }
    }

    @Test
    void expiredBusinessProofCompletesOnlyAckedInvalidationAndDoesNotClaimBusinessSuccess() {
        sessions.login("q5@example.com","Q5-test-password-01",null);
        UUID proof=UUID.randomUUID();
        assertThatThrownBy(() -> sessions.revokeForPasswordReset(user,proof)).isInstanceOf(ResponseStatusException.class);
        UUID id=jdbc.queryForObject("SELECT id FROM staff_revocation_operations WHERE user_id=?",UUID.class,user);
        revocations.recordAcknowledgement(id,acknowledgement(id));
        jdbc.update("UPDATE staff_revocation_operations SET resume_until=now()-interval '1 second' WHERE id=?",id);
        assertThatThrownBy(() -> sessions.revokeForPasswordReset(user,proof)).isInstanceOf(ResponseStatusException.class);
        revocations.expireBusiness(id);
        assertThat(revocations.load(id).businessOutcome()).isEqualTo("EXPIRED");
        assertThat(users.findById(user).orElseThrow().getPasswordChangedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL",Long.class)).isZero();
        sessions.login("q5@example.com","Q5-test-password-01",null);
    }

    @Test
    void bootstrapFreezesIssuanceAndRequiresAckForExactlyTheStoredFullBaseline() {
        var grant=sessions.login("q5@example.com","Q5-test-password-01",null);
        var baseline=bootstrap.prepare(restaurant,generation,UUID.randomUUID());
        assertThat(baseline.entries()).extracting(com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions.Entry::sessionId)
                .containsExactly(grant.session().getId());
        assertThatThrownBy(() -> sessions.login("q5@example.com","Q5-test-password-01",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> bootstrap.finish(baseline,"forged")).isInstanceOf(ResponseStatusException.class);
        var altered=new com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions.Baseline(
                baseline.operationId(),baseline.restaurantId(),baseline.generation(),baseline.nonce(),java.util.List.of());
        assertThatThrownBy(() -> bootstrap.finish(altered,baselineAcknowledgement(altered))).isInstanceOf(ResponseStatusException.class);
        assertThat(bootstrap.active()).isFalse();
        bootstrap.finish(baseline,baselineAcknowledgement(baseline));
        assertThat(bootstrap.active()).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM user_sessions WHERE id=?",Boolean.class,grant.session().getId())).isTrue();
        var fresh=sessions.login("q5@example.com","Q5-test-password-01",null);
        assertThat(fresh.session().getId()).isNotEqualTo(grant.session().getId());
        var duplicate=bootstrap.prepare(restaurant,generation,baseline.nonce());
        assertThat(duplicate).isEqualTo(baseline);
        bootstrap.finish(duplicate,baselineAcknowledgement(duplicate));
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM user_sessions WHERE id=?",Boolean.class,fresh.session().getId())).isTrue();
    }

    private String baselineAcknowledgement(com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions.Baseline baseline) {
        Instant now=Instant.now();
        return Jwts.builder().issuer("qr-service").subject(baseline.operationId().toString()).audience().add("restofy-auth").and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(30))).id(UUID.randomUUID().toString())
                .claim("token_type","service").claim("scope","staff:bootstrap:ack").claim("nonce",baseline.nonce().toString())
                .claim("restaurant_id",baseline.restaurantId().toString()).claim("generation",baseline.generation().toString())
                .claim("manifest",com.restofy.auth.application.service.revocation.RevocationBootstrapTransactions.digest(baseline))
                .signWith(Q5_QR_KEYS.getPrivate(),Jwts.SIG.RS256).compact();
    }

    @Test
    void deactivationCannotCommitBeforeAck() {
        sessions.login("q5@example.com", "Q5-test-password-01", null);
        assertThatThrownBy(() -> sessions.deactivate(user)).isInstanceOf(ResponseStatusException.class);
        assertThat(users.findById(user).orElseThrow().isActive()).isTrue();
        UUID id = jdbc.queryForObject("SELECT id FROM staff_revocation_operations WHERE user_id=?", UUID.class, user);
        revocations.recordAcknowledgement(id, acknowledgement(id));
        revocations.finalizeOperation(id);
        assertThat(users.findById(user).orElseThrow().isActive()).isFalse();
    }

    @Test
    void businessFinalizeRollsBackWithItsProtectedMutationAndCannotBeCompletedByWorker() {
        var grant = sessions.login("q5@example.com", "Q5-test-password-01", null);
        UUID proof = UUID.randomUUID();
        assertThatThrownBy(() -> sessions.revokeForPasswordReset(user, proof)).isInstanceOf(ResponseStatusException.class);
        UUID id = jdbc.queryForObject("SELECT id FROM staff_revocation_operations WHERE user_id=?", UUID.class, user);
        assertThat(revocations.load(id).purpose()).isEqualTo("RESET");
        revocations.recordAcknowledgement(id, acknowledgement(id));
        assertThatThrownBy(() -> revocations.finalizeOperation(id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            sessions.revokeForPasswordReset(user, proof);
            jdbc.update("UPDATE staff_users SET is_active=false WHERE id=?", user);
            throw new IllegalStateException("Protected mutation failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(revocations.load(id).status()).isEqualTo("ACKNOWLEDGED");
        assertThat(users.findById(user).orElseThrow().isActive()).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM user_sessions WHERE id=?", Boolean.class, grant.session().getId())).isTrue();
        assertThatThrownBy(() -> sessions.login("q5@example.com", "Q5-test-password-01", null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> sessions.revokeForPasswordReset(user, UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
        sessions.revokeForPasswordReset(user, proof);
        assertThat(revocations.load(id).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM user_sessions WHERE id=?", Boolean.class, grant.session().getId())).isTrue();
    }

    @Test
    void prepareDoesNotFinalizeAndAckIsMandatory() {
        var grant = sessions.login("q5@example.com", "Q5-test-password-01", null);
        UUID operation = UUID.randomUUID();
        var prepared = revocations.prepare(operation, restaurant, user, Action.DISABLE, null, null, generation);
        assertThat(prepared.targets()).extracting(RevocationTransactions.Target::sessionId).containsExactly(grant.session().getId());
        assertThatThrownBy(() -> revocations.finalizeOperation(operation)).isInstanceOf(ResponseStatusException.class);
        assertThat(users.findById(user).orElseThrow().isActive()).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM user_sessions WHERE id=?", Boolean.class,
                grant.session().getId())).isTrue();
        assertThatThrownBy(() -> revocations.recordAcknowledgement(operation, "forged"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(revocations.load(operation).status()).isEqualTo("PREPARED");
        assertThatThrownBy(() -> revocations.recordAcknowledgement(operation,
                acknowledgement(operation,builder -> builder.claim("manifest","0".repeat(64)))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> revocations.recordAcknowledgement(operation,
                acknowledgement(operation,builder -> builder.claim("scope","staff:read"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(revocations.load(operation).status()).isEqualTo("PREPARED");
        revocations.recordAcknowledgement(operation, acknowledgement(operation));
        revocations.finalizeOperation(operation);
        assertThat(users.findById(user).orElseThrow().isActive()).isFalse();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM user_sessions WHERE id=?", Boolean.class,
                grant.session().getId())).isTrue();
        assertThat(revocations.load(operation).status()).isEqualTo("COMPLETED");
        revocations.finalizeOperation(operation);
    }

    @Test
    void pendingGateAndManifestAreDurableAndIdempotent() {
        var grant = sessions.login("q5@example.com", "Q5-test-password-01", null);
        UUID id = UUID.randomUUID();
        var prepared = revocations.prepare(id, restaurant, user, Action.ALL, null, null, generation);
        assertThat(revocations.prepare(id, restaurant, user, Action.ALL, null, null, generation)).isEqualTo(prepared);
        assertThatThrownBy(() -> revocations.prepare(id, restaurant, user, Action.DISABLE, null, null, generation))
                .isInstanceOf(ResponseStatusException.class);
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            users.lockIdentity(user).orElseThrow();
            revocations.assertIssuanceAllowed(user);
        })).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> sessions.login("q5@example.com", "Q5-test-password-01", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> sessions.refresh(grant.refresh())).isInstanceOf(ResponseStatusException.class);
        revocations.recordAcknowledgement(id, acknowledgement(id));
        revocations.finalizeOperation(id);
        tx.executeWithoutResult(status -> revocations.assertIssuanceAllowed(user));
    }

    @Test
    void pendingGateBlocksMfaCredentialUpgrade() {
        jdbc.update("UPDATE staff_users SET role='ADMIN' WHERE id=?", user);
        var pending = sessions.login("q5@example.com", "Q5-test-password-01", null);
        revocations.prepare(UUID.randomUUID(), restaurant, user, Action.ALL, null, null, generation);
        assertThatThrownBy(() -> sessions.completeMfa(jwt.verify(pending.access(), true)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Long.class)).isZero();
    }

    @Test
    void roleChangeRevokesEveryOldSessionAndRefresh() {
        jdbc.update("UPDATE staff_users SET role='ADMIN' WHERE id=?",user);
        var first=sessions.login("q5@example.com", "Q5-test-password-01", null);
        var second=sessions.login("q5@example.com", "Q5-test-password-01", null);
        sessions.completeMfa(jwt.verify(first.access(),true));
        sessions.completeMfa(jwt.verify(second.access(),true));
        UUID id = UUID.randomUUID();
        revocations.prepare(id, restaurant, user, Action.ROLE, null, StaffRole.WAITER, generation);
        assertThat(users.findById(user).orElseThrow().getRole()).isEqualTo(StaffRole.ADMIN);
        revocations.recordAcknowledgement(id, acknowledgement(id));
        revocations.finalizeOperation(id);
        assertThat(users.findById(user).orElseThrow().getRole()).isEqualTo(StaffRole.WAITER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Long.class)).isZero();
    }

    @Test
    void failedFinalizeKeepsPendingGateAndAcknowledgement() {
        UUID id = UUID.randomUUID();
        revocations.prepare(id, restaurant, user, Action.DISABLE, null, null, generation);
        revocations.recordAcknowledgement(id, acknowledgement(id));
        jdbc.update("UPDATE staff_users SET version=version+1 WHERE id=?", user);
        assertThatThrownBy(() -> revocations.finalizeOperation(id)).isInstanceOf(ResponseStatusException.class);
        assertThat(revocations.load(id).status()).isEqualTo("ACKNOWLEDGED");
        assertThat(users.findById(user).orElseThrow().isActive()).isTrue();
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> revocations.assertIssuanceAllowed(user)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void mfaMintLocksFirstAndPrepareIncludesItsSessionBeforeClosingIssuance() throws Exception {
        var pending = pendingAdmin();
        var identity = jwt.verify(pending.access(),true);
        var minted = new CountDownLatch(1);
        var releaseMint = new CountDownLatch(1);
        var mintPid = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor=Executors.newFixedThreadPool(2)) {
            var producer=executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                mintPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                var grant=sessions.completeMfa(identity);
                minted.countDown();
                awaitLatch(releaseMint);
                return grant;
            }));
            try {
                assertThat(minted.await(10,TimeUnit.SECONDS)).isTrue();
                var prepare=executor.submit(() -> revocations.prepare(UUID.randomUUID(),restaurant,user,Action.ALL,null,null,generation));
                awaitBlockedOn(mintPid.get());
                assertThat(prepare.isDone()).isFalse();
                releaseMint.countDown();
                var grant=producer.get(10,TimeUnit.SECONDS);
                var operation=prepare.get(10,TimeUnit.SECONDS);
                assertThat(jwt.verify(grant.access(),false).sessionId()).isEqualTo(pending.session().getId());
                assertThat(operation.targets()).extracting(RevocationTransactions.Target::sessionId)
                        .containsExactly(grant.session().getId());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens",Long.class)).isEqualTo(1);
                assertThatThrownBy(() -> sessions.refresh(grant.refresh())).isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
            } finally { releaseMint.countDown(); }
        }
    }

    @Test
    void prepareLocksFirstAndMfaCannotCommitCredentialsPastPendingBarrier() throws Exception {
        var pending=pendingAdmin();
        var identity=jwt.verify(pending.access(),true);
        var prepared=new CountDownLatch(1);
        var releasePrepare=new CountDownLatch(1);
        var preparePid=new java.util.concurrent.atomic.AtomicInteger();
        try (var executor=Executors.newFixedThreadPool(2)) {
            var preparation=executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                preparePid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                var operation=revocations.prepareLocked(restaurant,user,Action.ALL,null,generation);
                prepared.countDown();
                awaitLatch(releasePrepare);
                return operation;
            }));
            try {
                assertThat(prepared.await(10,TimeUnit.SECONDS)).isTrue();
                var mint=executor.submit(() -> {
                    try { sessions.completeMfa(identity); return 200; }
                    catch (ResponseStatusException denied) { return denied.getStatusCode().value(); }
                });
                awaitBlockedOn(preparePid.get());
                assertThat(mint.isDone()).isFalse();
                releasePrepare.countDown();
                var operation=preparation.get(10,TimeUnit.SECONDS);
                assertThat(mint.get(10,TimeUnit.SECONDS)).isEqualTo(503);
                assertThat(operation.targets()).extracting(RevocationTransactions.Target::sessionId).containsExactly(pending.session().getId());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens",Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT auth_state FROM user_sessions WHERE id=?",String.class,pending.session().getId())).isEqualTo("MFA_PENDING");
            } finally { releasePrepare.countDown(); }
        }
    }

    private StaffSessionService.Grant pendingAdmin() {
        jdbc.update("UPDATE staff_users SET role='ADMIN',version=version+1 WHERE id=?",user);
        return sessions.login("q5@example.com","Q5-test-password-01",null);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Transaction release timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private void awaitBlockedOn(int blockerPid) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid)))",Boolean.class,blockerPid))) return;
            Thread.sleep(20);
        } while (System.nanoTime()<deadline);
        throw new AssertionError("Expected real PostgreSQL lock contention was not observed");
    }

    @Test
    void concurrentOperationsSerializeOnTheUserIdentity() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> prepareAfter(start));
            var second = executor.submit(() -> prepareAfter(start));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations", Long.class)).isEqualTo(1);
    }

    private int prepareAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            revocations.prepare(UUID.randomUUID(), restaurant, user, Action.ALL, null, null, generation);
            return 1;
        } catch (ResponseStatusException expectedConflict) {
            return 0;
        }
    }

    private String acknowledgement(UUID id) {
        return acknowledgement(id,builder -> { });
    }

    private String acknowledgement(UUID id,java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> alter) {
        var operation = revocations.load(id);
        Instant now = Instant.now();
        var builder=Jwts.builder().issuer("qr-service").subject(id.toString()).audience().add("restofy-auth").and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(30)))
                .id(UUID.randomUUID().toString()).claim("scope", "staff:revocation:ack").claim("token_type", "service")
                .claim("restaurant_id", operation.restaurantId().toString()).claim("generation", operation.consumerGeneration().toString())
                .claim("manifest", QrRevocationReceipts.manifestDigest(operation));
        alter.accept(builder);
        return builder.signWith(Q5_QR_KEYS.getPrivate(), Jwts.SIG.RS256).compact();
    }
}
