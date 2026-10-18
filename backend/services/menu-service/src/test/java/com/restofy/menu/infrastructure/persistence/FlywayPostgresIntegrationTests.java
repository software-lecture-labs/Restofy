package com.restofy.menu.infrastructure.persistence;

import com.restofy.testsupport.flyway.ValidationProbe;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FlywayPostgresIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("flyway_control");

    @TempDir
    Path migrations;
    private String database;

    @BeforeAll
    static void startIsolatedPostgres() {
        // A new container with no Compose network, existing volume, or reuse option.
        try {
            POSTGRES.start();
        } catch (RuntimeException failure) {
            POSTGRES.stop();
            throw failure;
        }
    }

    @AfterAll
    static void stopIsolatedPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createIndependentDatabase() throws Exception {
        database = newDatabase();
    }

    private String newDatabase() throws Exception {
        String name = "flyway_case_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private String url(String name) {
        assertTrue(name.matches("flyway_case_[a-f0-9]{32}"), "Only a database created by this test is allowed");
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    private Connection connection(String name) throws Exception {
        return DriverManager.getConnection(url(name), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String scalar(String name, String sql) throws Exception {
        try (var connection = connection(name); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private void sql(String name, String sql) throws Exception {
        try (var connection = connection(name); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<String> versions(String name) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = connection(name); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version FROM public.flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private void fixture(String folder, String file) throws Exception {
        try (var input = getClass().getResourceAsStream("/flyway/fixtures/" + folder + "/" + file)) {
            assertNotNull(input);
            Files.copy(input, migrations.resolve(file));
        }
    }

    private void firstMigration() throws Exception {
        fixture("v1", "V1__create_probe_table.sql");
    }

    private void secondMigration() throws Exception {
        fixture("v2", "V2__add_probe_index.sql");
    }

    private String location() {
        return "filesystem:" + migrations.toAbsolutePath().toString().replace('\\', '/');
    }

    private Flyway flyway(String name) {
        return Flyway.configure().dataSource(url(name), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location()).load();
    }

    private ApplicationContextRunner boot(boolean includeTestEntity, boolean productionLocation) {
        var configurations = includeTestEntity
                ? AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class, HibernateJpaAutoConfiguration.class)
                : AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class);
        var runner = new ApplicationContextRunner().withConfiguration(configurations)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    try {
                        sources.addLast(new ResourcePropertySource("classpath:application.properties"));
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                })
                .withPropertyValues("spring.datasource.url=" + url(database),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver");
        if (!productionLocation) runner = runner.withPropertyValues("spring.flyway.locations=" + location());
        if (includeTestEntity) runner = runner.withUserConfiguration(ProbeEntityConfiguration.class);
        return runner;
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ValidationProbe.class)
    static class ProbeEntityConfiguration { }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    @Test
    void bootStartupMigratesAndRecordsHistoryInPublicWithSafeDefaults() throws Exception {
        firstMigration();
        boot(false, false).run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(Flyway.class).getConfiguration();
            assertTrue(config.isCleanDisabled());
            assertFalse(config.isBaselineOnMigrate());
            assertFalse(config.isOutOfOrder());
            assertTrue(config.isValidateOnMigrate());
            assertTrue(config.isExecuteInTransaction());
            assertEquals("validate", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("1", scalar(database, "SELECT count(*) FROM public.migration_probe"));
            assertEquals(List.of("1"), versions(database));
            assertEquals("1", scalar(database, "SELECT count(*) FROM public.flyway_schema_history WHERE success AND checksum IS NOT NULL AND type='SQL'"));
            assertEquals("public", scalar(database, "SELECT current_schema()"));
            assertEquals("0", scalar(database, "SELECT count(*) FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') AND nspname NOT LIKE 'pg_%'"));
        });
    }

    @Test
    void productionLocationStartsAndAppliesOnlyShippedMigrations() {
        boot(false, true).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("classpath:db/migration", context.getEnvironment().getProperty("spring.flyway.locations"));
            // V61 (categories), V62 (products), V63 (availability), V64 (ingredients text), V65 (options) and V66 (nutrition bounds) are the shipped migrations; update this list when a new one is added.
            assertEquals(List.of("61", "62", "63", "64", "65", "66"), versions(database));
            assertEquals("6", scalar(database, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"));
            assertNotNull(scalar(database, "SELECT to_regclass('public.categories')"));
            assertNotNull(scalar(database, "SELECT to_regclass('public.products')"));
            assertNotNull(scalar(database, "SELECT to_regclass('public.product_availability')"));
            assertNotNull(scalar(database, "SELECT to_regclass('public.product_availability_history')"));
            assertNotNull(scalar(database, "SELECT to_regclass('public.product_options')"));
        });
    }

    @Test
    void appliedVersionIsNotRunAgainAndDataIsPreserved() throws Exception {
        firstMigration();
        var client = flyway(database);
        assertEquals(1, client.migrate().migrationsExecuted);
        assertEquals(0, client.migrate().migrationsExecuted);
        assertEquals("1", scalar(database, "SELECT count(*) FROM public.migration_probe"));
        assertEquals(List.of("1"), versions(database));
    }

    @Test
    void addingSecondVersionExecutesOnlyTheNewMigration() throws Exception {
        firstMigration();
        assertEquals(1, flyway(database).migrate().migrationsExecuted);
        String originalChecksum = scalar(database, "SELECT checksum FROM public.flyway_schema_history WHERE version='1'");
        secondMigration();
        assertEquals(1, flyway(database).migrate().migrationsExecuted);
        assertEquals(List.of("1", "2"), versions(database));
        assertEquals(originalChecksum, scalar(database, "SELECT checksum FROM public.flyway_schema_history WHERE version='1'"));
        assertEquals("2", scalar(database, "SELECT count(*) FROM public.migration_probe"));
        assertNotNull(scalar(database, "SELECT to_regclass('public.migration_probe_note_idx')"));
    }

    @Test
    void migrationOrderDependsOnVersionRatherThanFileCreationOrder() throws Exception {
        secondMigration();
        firstMigration();
        assertEquals(2, flyway(database).migrate().migrationsExecuted);
        assertEquals(List.of("1", "2"), versions(database));
        assertEquals("2", scalar(database, "SELECT count(*) FROM public.migration_probe"));
    }

    @Test
    void changedChecksumRejectsValidationMigrationAndBootStartup() throws Exception {
        firstMigration();
        flyway(database).migrate();
        var first = migrations.resolve("V1__create_probe_table.sql");
        Files.writeString(first, Files.readString(first).replace("first migration", "changed migration"));
        assertThrows(FlywayValidateException.class, () -> flyway(database).validate());
        assertThrows(FlywayValidateException.class, () -> flyway(database).migrate());
        boot(false, false).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(hasCause(context.getStartupFailure(), FlywayValidateException.class));
        });
        assertEquals("first migration", scalar(database, "SELECT note FROM public.migration_probe WHERE id=1"));
        assertEquals(List.of("1"), versions(database));
    }

    @Test
    void invalidFirstMigrationFailsStartupAndRollsBackPostgresDdl() throws Exception {
        Files.writeString(migrations.resolve("V1__invalid_sql.sql"),
                "CREATE TABLE public.failed_probe (id BIGINT PRIMARY KEY);\nTHIS IS NOT VALID SQL;\n");
        boot(false, false).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(hasCause(context.getStartupFailure(), FlywayException.class));
        });
        assertNull(scalar(database, "SELECT to_regclass('public.failed_probe')"));
        assertEquals(List.of(), versions(database));
    }

    @Test
    void failedNewVersionRollsBackAndPreservesExistingHistoryAndData() throws Exception {
        firstMigration();
        flyway(database).migrate();
        Files.writeString(migrations.resolve("V2__invalid_sql.sql"),
                "INSERT INTO public.migration_probe VALUES (2, 'must roll back');\n"
                        + "CREATE TABLE public.failed_probe (id BIGINT PRIMARY KEY);\nTHIS IS NOT VALID SQL;\n");
        assertThrows(FlywayException.class, () -> flyway(database).migrate());
        assertNull(scalar(database, "SELECT to_regclass('public.failed_probe')"));
        assertEquals("1", scalar(database, "SELECT count(*) FROM public.migration_probe"));
        assertEquals(List.of("1"), versions(database));
    }

    @Test
    void twoDatabasesHaveIndependentTablesAndVersionHistories() throws Exception {
        String otherDatabase = newDatabase();
        firstMigration();
        flyway(database).migrate();
        assertNull(scalar(otherDatabase, "SELECT to_regclass('public.migration_probe')"));
        assertNull(scalar(otherDatabase, "SELECT to_regclass('public.flyway_schema_history')"));
        secondMigration();
        assertEquals(2, flyway(otherDatabase).migrate().migrationsExecuted);
        assertEquals(List.of("1"), versions(database));
        assertEquals(List.of("1", "2"), versions(otherDatabase));
        assertNull(scalar(database, "SELECT to_regclass('public.migration_probe_note_idx')"));
        assertEquals("1", scalar(database, "SELECT count(*) FROM public.migration_probe"));
        assertEquals("2", scalar(otherDatabase, "SELECT count(*) FROM public.migration_probe"));
    }

    @Test
    void cleanIsBlockedAndLeavesTablesAndHistoryIntact() throws Exception {
        firstMigration();
        flyway(database).migrate();
        var failure = assertThrows(FlywayException.class, () -> flyway(database).clean());
        assertTrue(failure.getMessage().contains("cleanDisabled"));
        assertEquals(List.of("1"), versions(database));
        assertEquals("1", scalar(database, "SELECT count(*) FROM public.migration_probe"));
    }

    @Test
    void nonEmptyDatabaseIsNotAutomaticallyBaselined() throws Exception {
        sql(database, "CREATE TABLE public.existing_probe (id BIGINT PRIMARY KEY)");
        firstMigration();
        boot(false, false).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(hasCause(context.getStartupFailure(), FlywayException.class));
        });
        assertNull(scalar(database, "SELECT to_regclass('public.migration_probe')"));
        assertNull(scalar(database, "SELECT to_regclass('public.flyway_schema_history')"));
        assertNotNull(scalar(database, "SELECT to_regclass('public.existing_probe')"));
    }

    @Test
    void lateLowerVersionIsRejectedInsteadOfAppliedOutOfOrder() throws Exception {
        firstMigration();
        Files.writeString(migrations.resolve("V3__create_later_probe.sql"), "CREATE TABLE public.later_probe (id BIGINT PRIMARY KEY);\n");
        flyway(database).migrate();
        secondMigration();
        assertThrows(FlywayValidateException.class, () -> flyway(database).migrate());
        assertEquals(List.of("1", "3"), versions(database));
        assertNull(scalar(database, "SELECT to_regclass('public.migration_probe_note_idx')"));
        assertEquals("1", scalar(database, "SELECT count(*) FROM public.migration_probe"));
    }

    @Test
    void duplicateVersionsAreRejectedBeforeApplyingTables() throws Exception {
        firstMigration();
        Files.writeString(migrations.resolve("V1__duplicate_version.sql"), "CREATE TABLE public.duplicate_probe (id BIGINT);\n");
        assertThrows(FlywayException.class, () -> flyway(database).migrate());
        assertNull(scalar(database, "SELECT to_regclass('public.migration_probe')"));
        assertNull(scalar(database, "SELECT to_regclass('public.duplicate_probe')"));
    }

    @Test
    void hibernateValidateRejectsMissingTableWithoutCreatingIt() {
        boot(true, false).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(hasCause(context.getStartupFailure(), SchemaManagementException.class));
            assertNull(scalar(database, "SELECT to_regclass('public.hibernate_probe')"));
        });
    }

    @Test
    void flywayRunsBeforeHibernateValidateAndMatchingFixtureStarts() throws Exception {
        fixture("hibernate", "V1__create_hibernate_probe.sql");
        boot(true, false).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
            assertEquals(List.of("1"), versions(database));
            assertEquals("0", scalar(database, "SELECT count(*) FROM public.hibernate_probe"));
        });
    }

    @Test
    void hibernateValidateRejectsMissingColumnWithoutAddingIt() throws Exception {
        Files.writeString(migrations.resolve("V1__incomplete_hibernate_probe.sql"), "CREATE TABLE public.hibernate_probe (id BIGINT PRIMARY KEY);\n");
        boot(true, false).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(hasCause(context.getStartupFailure(), SchemaManagementException.class));
            assertEquals("0", scalar(database, "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='hibernate_probe' AND column_name='note'"));
            assertEquals(List.of("1"), versions(database));
        });
    }
}
