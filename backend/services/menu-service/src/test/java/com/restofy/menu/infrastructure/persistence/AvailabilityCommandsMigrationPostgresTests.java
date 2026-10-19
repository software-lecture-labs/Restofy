package com.restofy.menu.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V67 against a database that already holds products, availability and history (state after V66): nothing existing is
 * changed, the new column is NULL on old history, and the new constraints keep every command inside its restaurant.
 * Own throw-away container, never the DEV database.
 */
class AvailabilityCommandsMigrationPostgresTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("commands_control");

    private static final String OLD_ROWS = "SELECT status, reason_code, version FROM product_availability ORDER BY product_id";
    private static final String OLD_HISTORY = "SELECT from_status, to_status, reason_code, changed_by_user_id FROM product_availability_history ORDER BY id";

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private String newDatabase() throws Exception {
        String name = "commands_case_" + UUID.randomUUID().toString().replace("-", "");
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

    private List<String> rows(String url, String sql) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = connect(url); var statement = connection.createStatement(); var set = statement.executeQuery(sql)) {
            int columns = set.getMetaData().getColumnCount();
            while (set.next()) {
                var row = new StringBuilder();
                for (int i = 1; i <= columns; i++) row.append(i > 1 ? "|" : "").append(set.getString(i));
                result.add(row.toString());
            }
        }
        return result;
    }

    private void execute(String url, String sql) throws Exception {
        try (var connection = connect(url); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertRejected(String url, String constraint, String sql) {
        var failure = assertThrows(SQLException.class, () -> execute(url, sql));
        assertTrue(failure.getMessage().contains(constraint), failure.getMessage());
    }

    private record Seed(UUID restaurant, UUID product, UUID actor) { }

    private Seed seedProduct(String url, String name, String status, String reason) throws Exception {
        var seed = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID category = UUID.randomUUID();
        execute(url, "INSERT INTO categories (id, restaurant_id, name) VALUES ('" + category + "', '" + seed.restaurant() + "', 'Main')");
        execute(url, "INSERT INTO products (id, restaurant_id, category_id, name, base_price, currency, vat_rate, price_includes_vat, "
                + "created_by_user_id, updated_by_user_id) VALUES ('" + seed.product() + "', '" + seed.restaurant() + "', '" + category + "', '"
                + name + "', 5, 'TRY', 10, TRUE, '" + seed.actor() + "', '" + seed.actor() + "')");
        execute(url, "INSERT INTO product_availability (id, restaurant_id, product_id, status, reason_code, changed_at, changed_by_user_id) "
                + "VALUES ('" + UUID.randomUUID() + "', '" + seed.restaurant() + "', '" + seed.product() + "', '" + status + "', "
                + (reason == null ? "NULL" : "'" + reason + "'") + ", now(), '" + seed.actor() + "')");
        execute(url, "INSERT INTO product_availability_history (id, restaurant_id, product_id, from_status, to_status, reason_code, "
                + "changed_by_user_id, changed_at) VALUES ('" + UUID.randomUUID() + "', '" + seed.restaurant() + "', '" + seed.product()
                + "', NULL, '" + status + "', " + (reason == null ? "NULL" : "'" + reason + "'") + ", '" + seed.actor() + "', now())");
        return seed;
    }

    private String command(Seed seed, UUID commandId, String status, String reason) {
        return "INSERT INTO product_availability_commands (restaurant_id, command_id, product_id, source, target_status, reason_code, "
                + "outcome, resulting_version, requested_by_user_id, created_at) VALUES ('" + seed.restaurant() + "', '" + commandId + "', '"
                + seed.product() + "', 'KITCHEN', '" + status + "', " + (reason == null ? "NULL" : "'" + reason + "'")
                + ", 'APPLIED', 1, '" + seed.actor() + "', now())";
    }

    @Test
    void existingAvailabilityAndHistoryAreKeptByteForByte() throws Exception {
        String url = newDatabase();
        flyway(url, "66").migrate();
        seedProduct(url, "open", "AVAILABLE", null);
        seedProduct(url, "closed", "OUT_OF_STOCK", "SOLD_OUT");
        seedProduct(url, "backfilled", "TEMPORARILY_CLOSED", "MIGRATION_BACKFILL");
        var availabilityBefore = rows(url, OLD_ROWS);
        var historyBefore = rows(url, OLD_HISTORY);

        var result = flyway(url, "67").migrate();

        assertEquals(1, result.migrationsExecuted);
        assertEquals(availabilityBefore, rows(url, OLD_ROWS), "no availability row changed, nothing was reopened");
        assertEquals(historyBefore, rows(url, OLD_HISTORY), "no history entry changed");
        assertEquals(List.of("3"), rows(url, "SELECT count(*) FROM product_availability_history WHERE command_id IS NULL"),
                "old history has no command and nothing is back-filled");
        assertEquals(List.of("0"), rows(url, "SELECT count(*) FROM product_availability_commands"));
        assertEquals(List.of("67"), rows(url, "SELECT max(version::int)::text FROM flyway_schema_history"));
        assertEquals(0, flyway(url, "67").migrate().migrationsExecuted, "an applied migration is never run again");
    }

    @Test
    void anEmptyDatabaseMigratesCleanlyThroughV67() throws Exception {
        String url = newDatabase();
        assertEquals(7, flyway(url, "67").migrate().migrationsExecuted);
        assertEquals(List.of("command_id|uuid|YES"), rows(url, "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'product_availability_history' AND column_name = 'command_id'"));
    }

    @Test
    void aCommandBelongsToItsRestaurantAndItsProduct() throws Exception {
        String url = newDatabase();
        flyway(url, "67").migrate();
        Seed seed = seedProduct(url, "tea", "AVAILABLE", null);
        Seed other = seedProduct(url, "cake", "AVAILABLE", null);
        UUID commandId = UUID.randomUUID();

        execute(url, command(seed, commandId, "OUT_OF_STOCK", "SOLD_OUT"));

        // The same id in another restaurant is another command; the same id again in this restaurant is refused.
        execute(url, command(other, commandId, "OUT_OF_STOCK", "SOLD_OUT"));
        assertRejected(url, "pk_product_availability_commands", command(seed, commandId, "OUT_OF_STOCK", "SOLD_OUT"));
        // A product of another restaurant cannot be named by this restaurant's command.
        String crossTenant = command(seed, UUID.randomUUID(), "OUT_OF_STOCK", "SOLD_OUT").replace(seed.product().toString(), other.product().toString());
        assertRejected(url, "fk_product_availability_commands_product", crossTenant);
        assertEquals(List.of("2"), rows(url, "SELECT count(*) FROM product_availability_commands"));
    }

    @Test
    void theCommandRecordRejectsInvalidContent() throws Exception {
        String url = newDatabase();
        flyway(url, "67").migrate();
        Seed seed = seedProduct(url, "tea", "AVAILABLE", null);
        assertRejected(url, "ck_product_availability_commands_closed_reason", command(seed, UUID.randomUUID(), "OUT_OF_STOCK", null));
        assertRejected(url, "ck_product_availability_commands_status", command(seed, UUID.randomUUID(), "BROKEN", "X"));
        assertRejected(url, "ck_product_availability_commands_source", command(seed, UUID.randomUUID(), "AVAILABLE", null).replace("'KITCHEN'", "'ORDER'"));
        assertRejected(url, "ck_product_availability_commands_outcome", command(seed, UUID.randomUUID(), "AVAILABLE", null).replace("'APPLIED'", "'MAYBE'"));
        assertRejected(url, "ck_product_availability_commands_versions", command(seed, UUID.randomUUID(), "AVAILABLE", null).replace("'APPLIED', 1", "'APPLIED', -1"));
        execute(url, command(seed, UUID.randomUUID(), "AVAILABLE", null));
    }

    @Test
    void aHistoryEntryMayNameOnlyItsOwnTenantsAndProductsCommandAndOnlyOnce() throws Exception {
        String url = newDatabase();
        flyway(url, "67").migrate();
        Seed seed = seedProduct(url, "tea", "AVAILABLE", null);
        Seed other = seedProduct(url, "cake", "AVAILABLE", null);
        UUID commandId = UUID.randomUUID();
        execute(url, command(seed, commandId, "OUT_OF_STOCK", "SOLD_OUT"));

        String history = "INSERT INTO product_availability_history (id, restaurant_id, product_id, from_status, to_status, reason_code, "
                + "changed_by_user_id, changed_at, command_id) VALUES ('%s', '%s', '%s', 'AVAILABLE', 'OUT_OF_STOCK', 'SOLD_OUT', '%s', now(), '%s')";
        assertRejected(url, "fk_product_availability_history_command", String.format(history, UUID.randomUUID(), other.restaurant(), other.product(), other.actor(), commandId));
        assertRejected(url, "fk_product_availability_history_command", String.format(history, UUID.randomUUID(), seed.restaurant(), seed.product(), seed.actor(), UUID.randomUUID()));
        execute(url, String.format(history, UUID.randomUUID(), seed.restaurant(), seed.product(), seed.actor(), commandId));
        assertRejected(url, "uq_product_availability_history_command", String.format(history, UUID.randomUUID(), seed.restaurant(), seed.product(), seed.actor(), commandId));
    }
}
