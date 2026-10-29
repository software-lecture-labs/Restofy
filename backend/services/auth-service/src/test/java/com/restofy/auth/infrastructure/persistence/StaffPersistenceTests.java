package com.restofy.auth.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.restofy.auth.application.service.StaffBootstrapService;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.StaffBootstrapProperties;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses only the Maven test profile's disposable jdbc:tc PostgreSQL database. */
@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
class StaffPersistenceTests extends AuthSessionTestSupport {
    private static final String TEST_PASSWORD = "Test-only-password-01";
    @Autowired private StaffUserRepository users;
    @Autowired private StaffBootstrapService bootstrap;
    @Autowired private StaffPasswords passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Environment environment;
    private String hash;

    @BeforeEach
    void setup() {
        // Second guard before fixture mutation; the initializer guards startup before Flyway.
        assertThat(environment.getRequiredProperty("spring.datasource.url")).startsWith("jdbc:tc:postgresql:");
        String url = jdbc.execute((ConnectionCallback<String>)
                connection -> connection.getMetaData().getURL());
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(url).contains("jdbc:postgresql:");
        assertThat(database).isEqualTo("restofy_test");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM user_sessions");
        users.deleteAll();
        hash = passwords.hash(TEST_PASSWORD);
    }

    @Test
    void flywayAppliesV1AndHibernateValidatesStaffSchema() {
        flyway.validate();
        assertThat(flyway.info().applied()).hasSize(4);
        assertThat(flyway.info().applied()[3].getVersion().getVersion()).isEqualTo("4");
        assertThat(flyway.info().applied()[2].getVersion().getVersion()).isEqualTo("3");
        assertThat(flyway.info().applied()[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().applied()[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='staff_users'", Integer.class))
                .isEqualTo(9);
        StaffUser saved = users.saveAndFlush(new StaffUser(" STAFF@EXAMPLE.COM ", hash, StaffRole.KITCHEN));
        assertThat(saved.getEmailNormalized()).isEqualTo("staff@example.com");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(users.findById(saved.getId())).isPresent();
    }

    @Test
    void databaseEnforcesEmailUniquenessIncludingConcurrentInserts() throws Exception {
        var outcomes = race(() -> insertOutcome("duplicate@example.com", hash),
                () -> insertOutcome("duplicate@example.com", hash));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(users.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN@EXAMPLE.COM", " admin@example.com", "not-email"})
    void databaseRejectsUnnormalizedOrInvalidEmail(String email) {
        assertThatThrownBy(() -> insert(email, hash, "ADMIN")).isInstanceOf(DataAccessException.class);
        assertThat(users.count()).isZero();
    }

    @Test
    void databaseRejectsUnknownRolePlaintextAndInvalidVersionOrDates() {
        assertThatThrownBy(() -> insert("staff@example.com", hash, "SUPERADMIN")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert("staff@example.com", TEST_PASSWORD, "ADMIN")).isInstanceOf(DataAccessException.class);
        UUID negativeVersionId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO staff_users"
                        + " (id,email_normalized,password_hash,role,created_at,updated_at,version) VALUES (?,?,?,'ADMIN',now(),now(),-1)",
                negativeVersionId, "staff@example.com", hash)).isInstanceOf(DataAccessException.class);
        UUID invalidDatesId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO staff_users"
                        + " (id,email_normalized,password_hash,role,created_at,updated_at)"
                        + " VALUES (?,?,?,'ADMIN',now(),now()-interval '1 second')",
                invalidDatesId, "staff@example.com", hash)).isInstanceOf(DataAccessException.class);
        assertThat(users.count()).isZero();
    }

    @Test
    void optimisticVersionPreventsStaleAccountUpdate() {
        StaffUser saved = users.saveAndFlush(new StaffUser("staff@example.com", hash, StaffRole.WAITER));
        StaffUser first = users.findById(saved.getId()).orElseThrow();
        StaffUser stale = users.findById(saved.getId()).orElseThrow();
        first.deactivate();
        StaffUser changed = users.saveAndFlush(first);
        assertThat(changed.getVersion()).isEqualTo(1);
        assertThat(changed.getUpdatedAt()).isAfterOrEqualTo(changed.getCreatedAt());
        stale.deactivate();
        assertThatThrownBy(() -> users.saveAndFlush(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void concurrentInitialBootstrapCreatesOnlyOneIdentityAndNeverResetsIt() throws Exception {
        var properties = initial("owner@example.com");
        var outcomes = race(() -> bootstrapOutcome(properties), () -> bootstrapOutcome(properties));
        assertThat(outcomes).containsOnly(true);
        assertThat(users.count()).isEqualTo(1);
        StaffUser original = users.findByEmailNormalized("owner@example.com").orElseThrow();
        original.deactivate();
        original = users.saveAndFlush(original);
        properties.getInitialAdmin().setPassword("Changed-test-only-password");
        bootstrap.initialize(properties, new String[]{"test"});
        StaffUser after = users.findById(original.getId()).orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(original.getPasswordHash());
        assertThat(after.isActive()).isFalse();
    }

    @Test
    void concurrentDifferentInitialAdminsCannotBothBeCreated() throws Exception {
        var outcomes = race(() -> bootstrapOutcome(initial("first@example.com")),
                () -> bootstrapOutcome(initial("second@example.com")));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void concurrentDevelopmentBootstrapIsIdempotent() throws Exception {
        var properties = development();
        assertThat(race(() -> bootstrapOutcome(properties), () -> bootstrapOutcome(properties))).containsOnly(true);
        assertThat(users.count()).isEqualTo(3);
        var before = jdbc.queryForList("SELECT id,password_hash,is_active,version FROM staff_users ORDER BY email_normalized");
        bootstrap.initialize(properties, new String[]{"test"});
        assertThat(jdbc.queryForList("SELECT id,password_hash,is_active,version FROM staff_users ORDER BY email_normalized"))
                .isEqualTo(before);
    }

    @Test
    void developmentConflictRollsBackAllNewAccounts() {
        users.saveAndFlush(new StaffUser("kitchen@example.com", hash, StaffRole.WAITER));
        var properties = development();
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"test"})).isInstanceOf(IllegalStateException.class);
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByEmailNormalized("admin@example.com")).isEmpty();
        assertThat(users.findByEmailNormalized("waiter@example.com")).isEmpty();
    }

    @Test
    void malformedHashCannotEnterThroughDomainModel() {
        assertThatThrownBy(() -> new StaffUser("staff@example.com", "plaintext", StaffRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private boolean bootstrapOutcome(StaffBootstrapProperties properties) {
        try {
            bootstrap.initialize(properties, new String[]{"test"});
            return true;
        } catch (IllegalStateException _) {
            return false;
        }
    }

    private boolean insertOutcome(String email, String passwordHash) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> insert(email, passwordHash, "ADMIN"));
            return true;
        } catch (DataAccessException _) {
            return false;
        }
    }

    private void insert(String email, String passwordHash, String role) {
        jdbc.update("INSERT INTO staff_users (id,email_normalized,password_hash,role,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), email, passwordHash, role,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private StaffBootstrapProperties initial(String email) {
        var properties = new StaffBootstrapProperties();
        properties.getInitialAdmin().setEnabled(true);
        properties.getInitialAdmin().setEmail(email);
        properties.getInitialAdmin().setPassword(TEST_PASSWORD);
        return properties;
    }

    private StaffBootstrapProperties development() {
        var properties = new StaffBootstrapProperties();
        properties.getDevUsers().setEnabled(true);
        var dev = properties.getDevUsers();
        dev.getAdmin().setEmail("admin@example.com");
        dev.getWaiter().setEmail("waiter@example.com");
        dev.getKitchen().setEmail("kitchen@example.com");
        for (var account : List.of(dev.getAdmin(), dev.getWaiter(), dev.getKitchen())) {
            account.setPassword(TEST_PASSWORD);
        }
        return properties;
    }

    private List<Boolean> race(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await();
            return first.call();
            });
            var b = executor.submit(() -> { start.await();
            return second.call();
            });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }
}
