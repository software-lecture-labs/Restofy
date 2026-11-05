package com.restofy.qr.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore.Command;
import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class StaffRevocationStoreTests extends com.restofy.qr.support.Q5TestSecuritySupport {
    @org.springframework.test.context.DynamicPropertySource
    static void durableDatabase(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:tc:q5postgresql:17-alpine:///restofy_test");
    }
    @Autowired StaffRevocationStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private UUID generation;
    private UUID restaurant;
    private UUID user;
    private UUID sid;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM staff_revocation_receipts");
        jdbc.update("DELETE FROM staff_revoked_sessions");
        jdbc.update("UPDATE staff_revocation_guard_state SET ready=false,generation=NULL,database_start=NULL");
        generation = UUID.randomUUID(); restaurant = UUID.randomUUID(); user = UUID.randomUUID(); sid = UUID.randomUUID();
    }

    @Test
    void sameProcessRestoreQuarantineMustInvalidateTheOldNonce() throws Exception {
        beginBootstrapFixture(generation);
        completeBootstrapFixture(generation);
        UUID oldNonce = store.challenge().nonce();
        var databaseStart = jdbc.queryForObject("SELECT pg_postmaster_start_time()", java.sql.Timestamp.class);
        store.apply(command(UUID.randomUUID(), sid, 1));
        // Restored data predates this revocation, without a PostgreSQL restart.
        jdbc.update("DELETE FROM staff_revocation_receipts");
        jdbc.update("DELETE FROM staff_revoked_sessions");
        jdbc.update("UPDATE staff_revocation_guard_state SET ready=false");
        assertThat(store.isReady()).isFalse();
        assertThat(store.challenge().nonce()).as("Negative control: ready=false alone reuses the old nonce").isEqualTo(oldNonce);
        var root=java.nio.file.Path.of("").toAbsolutePath();
        while (root!=null && !java.nio.file.Files.exists(root.resolve("ops/qr/q5-restore-quarantine.sql"))) root=root.getParent();
        assertThat(root).as("Repository restore quarantine script").isNotNull();
        jdbc.execute(java.nio.file.Files.readString(root.resolve("ops/qr/q5-restore-quarantine.sql")));
        assertThat(store.isReady()).isFalse();
        assertThat(store.challenge().nonce()).as("Restore must not reuse an acknowledged old baseline nonce").isNotEqualTo(oldNonce);
        assertThatThrownBy(() -> store.requireAllowed(restaurant,user,sid)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        UUID freshNonce=store.challenge().nonce();
        store.installBaseline(new StaffRevocationStore.Baseline(UUID.randomUUID(),restaurant,generation,freshNonce,
                List.of(new StaffRevocationStore.Entry(sid,user,Instant.parse("2030-01-01T00:00:00Z")))));
        assertThatThrownBy(() -> store.requireAllowed(restaurant,user,sid)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        assertThat(jdbc.queryForObject("SELECT pg_postmaster_start_time()", java.sql.Timestamp.class)).isEqualTo(databaseStart);
    }

    @Test
    void baselineIsClosedAndAllInstancesReadOneDurableStore() {
        assertThatThrownBy(() -> store.requireAllowed(restaurant, user, sid)).isInstanceOf(ResponseStatusException.class);
        beginBootstrapFixture(generation);
        assertThatThrownBy(() -> store.requireAllowed(restaurant, user, sid)).isInstanceOf(ResponseStatusException.class);
        completeBootstrapFixture(generation);
        store.requireAllowed(restaurant, user, sid);
        store.apply(command(UUID.randomUUID(), sid, 1));
        assertThatThrownBy(() -> store.requireAllowed(restaurant, user, sid)).isInstanceOf(ResponseStatusException.class);
        var secondInstance = new StaffRevocationStore(jdbc, java.time.Clock.systemUTC());
        assertThatThrownBy(() -> secondInstance.requireAllowed(restaurant, user, sid)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void duplicateProducesSameReceiptButAlteredManifestIsRejected() {
        beginBootstrapFixture(generation);
        Command command = command(UUID.randomUUID(), sid, 1);
        var receipt = store.apply(command);
        assertThat(store.apply(command)).isEqualTo(receipt);
        assertThatThrownBy(() -> store.apply(command(command.operationId(), UUID.randomUUID(), 1)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revoked_sessions", Long.class)).isEqualTo(1);
    }

    @Test
    void outOfOrderCommandsNeverRemoveAnEarlierSidDenial() {
        beginBootstrapFixture(generation);
        UUID other = UUID.randomUUID();
        store.apply(command(UUID.randomUUID(), other, 2));
        store.apply(command(UUID.randomUUID(), sid, 1));
        completeBootstrapFixture(generation);
        assertThatThrownBy(() -> store.requireAllowed(restaurant, user, other)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.requireAllowed(restaurant, user, sid)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void noAckRecordSurvivesDatabaseRollback() {
        beginBootstrapFixture(generation);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            store.apply(command(UUID.randomUUID(), sid, 1));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_receipts", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revoked_sessions", Long.class)).isZero();
    }

    @Test
    void changedDatabaseIncarnationAndMissingGuardFailClosed() {
        beginBootstrapFixture(generation);
        completeBootstrapFixture(generation);
        jdbc.update("UPDATE staff_revocation_guard_state SET database_start=database_start-interval '1 second'");
        assertThatThrownBy(() -> store.requireAllowed(restaurant, user, sid)).isInstanceOf(ResponseStatusException.class);
        jdbc.update("DELETE FROM staff_revocation_guard_state");
        assertThatThrownBy(() -> store.requireAllowed(restaurant, user, sid)).isInstanceOf(RuntimeException.class);
        jdbc.update("INSERT INTO staff_revocation_guard_state(singleton) VALUES (true)");
    }

    private Command command(UUID operation, UUID session, long revision) {
        return new Command(operation, restaurant, user, revision, generation,
                List.of(new Target(session, Instant.parse("2030-01-01T00:00:00Z"))));
    }
    // Explicit test-only state fixtures. Production readiness is opened only by signed full-baseline installation.
    private void beginBootstrapFixture(UUID generation) {
        jdbc.update("UPDATE staff_revocation_guard_state SET ready=false,generation=?,database_start=NULL,bootstrap_nonce=NULL WHERE singleton=true",generation);
    }

    private void completeBootstrapFixture(UUID generation) {
        jdbc.update("UPDATE staff_revocation_guard_state SET generation=?,ready=true,database_start=pg_postmaster_start_time(),bootstrap_nonce=? WHERE singleton=true",generation,UUID.randomUUID());
    }

}
