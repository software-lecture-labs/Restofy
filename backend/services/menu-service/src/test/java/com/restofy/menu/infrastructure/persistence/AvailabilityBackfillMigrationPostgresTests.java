package com.restofy.menu.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V63 against a database that already holds products created before availability existed (state after
 * V62). Runs on its own throw-away PostgreSQL container and database; nothing else is touched.
 */
class AvailabilityBackfillMigrationPostgresTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("backfill_control");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private String newDatabase() throws Exception {
        String name = "backfill_case_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(true).load();
    }

    private Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertProduct(Connection connection, UUID restaurant, UUID category, UUID product, UUID creator, UUID updater) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("INSERT INTO categories (id, restaurant_id, name) VALUES ('" + category + "', '" + restaurant + "', 'Cat " + category + "')");
            statement.execute("INSERT INTO products (id, restaurant_id, category_id, name, base_price, currency, vat_rate, "
                    + "price_includes_vat, is_published, created_by_user_id, updated_by_user_id) VALUES ('" + product + "', '"
                    + restaurant + "', '" + category + "', 'Old tea', 5, 'TRY', 10, TRUE, TRUE, '" + creator + "', '" + updater + "')");
        }
    }

    private List<String> rows(Connection connection, String sql) throws Exception {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement(); var set = statement.executeQuery(sql)) {
            int columns = set.getMetaData().getColumnCount();
            while (set.next()) {
                var row = new StringBuilder();
                for (int i = 1; i <= columns; i++) row.append(i > 1 ? "|" : "").append(set.getString(i));
                result.add(row.toString());
            }
        }
        return result;
    }

    @Test
    void existingProductsAreBackfilledAsClosedNeverAvailableAndNothingIsDuplicated() throws Exception {
        String url = newDatabase();
        flyway(url, "62").migrate();

        UUID restaurantA = UUID.randomUUID();
        UUID restaurantB = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID updaterA = UUID.randomUUID();
        UUID updaterB = UUID.randomUUID();
        try (var connection = connect(url)) {
            insertProduct(connection, restaurantA, UUID.randomUUID(), productA, UUID.randomUUID(), updaterA);
            insertProduct(connection, restaurantB, UUID.randomUUID(), productB, UUID.randomUUID(), updaterB);
        }

        var result = flyway(url, "63").migrate();
        assertEquals(1, result.migrationsExecuted);

        try (var connection = connect(url)) {
            assertEquals(List.of("2"), rows(connection, "SELECT count(*) FROM product_availability"));
            assertEquals(List.of("0"), rows(connection, "SELECT count(*) FROM product_availability WHERE status = 'AVAILABLE'"),
                    "an unknown state must never reopen a product");
            assertEquals(List.of("2"), rows(connection, "SELECT count(*) FROM product_availability "
                    + "WHERE status = 'TEMPORARILY_CLOSED' AND reason_code = 'MIGRATION_BACKFILL' AND reason_text IS NOT NULL"));
            assertEquals(List.of(productA + "|" + restaurantA + "|" + updaterA),
                    rows(connection, "SELECT product_id, restaurant_id, changed_by_user_id FROM product_availability WHERE product_id = '" + productA + "'"));
            assertEquals(List.of(productB + "|" + restaurantB + "|" + updaterB),
                    rows(connection, "SELECT product_id, restaurant_id, changed_by_user_id FROM product_availability WHERE product_id = '" + productB + "'"));
            // The backfill is recorded in the history too, with no previous status.
            assertEquals(List.of("2"), rows(connection, "SELECT count(*) FROM product_availability_history "
                    + "WHERE from_status IS NULL AND to_status = 'TEMPORARILY_CLOSED' AND reason_code = 'MIGRATION_BACKFILL'"));
            // The other axes of the old product are untouched.
            assertEquals(List.of("t|t"), rows(connection, "SELECT is_active, is_published FROM products WHERE id = '" + productA + "'"));
        }
    }

    @Test
    void anEmptyDatabaseBackfillsNothingAndRerunningMigrateAddsNothing() throws Exception {
        String url = newDatabase();
        flyway(url, "63").migrate();
        var again = flyway(url, "63").migrate();
        assertEquals(0, again.migrationsExecuted, "an applied migration is never run again");
        try (var connection = connect(url)) {
            assertEquals(List.of("0"), rows(connection, "SELECT count(*) FROM product_availability"));
            assertEquals(List.of("0"), rows(connection, "SELECT count(*) FROM product_availability_history"));
        }
    }
}
