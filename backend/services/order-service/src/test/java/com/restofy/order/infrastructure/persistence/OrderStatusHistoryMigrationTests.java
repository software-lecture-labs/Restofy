package com.restofy.order.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Runs the migrations in two steps in a throw-away schema of the test database: first up to V44 (the state of an
 * existing deployment that already holds orders), then V45. Proves the backfill gives old orders a complete timeline.
 */
@SpringBootTest
class OrderStatusHistoryMigrationTests {

    @Autowired
    private DataSource dataSource;

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target).load();
    }

    @Test
    void ordersThatExistBeforeV45GetAnInitialRowPerAxis() throws Exception {
        String schema = "history_backfill_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, "44").migrate();

            UUID restaurant = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("INSERT INTO orders (id, restaurant_id, order_no, cart_id, table_id, table_session_id,"
                        + " guest_session_id, fulfillment_status, payment_status, currency, initial_net_minor,"
                        + " initial_vat_minor, initial_gross_minor, current_due_minor, placed_at, version, created_at,"
                        + " updated_at) VALUES ('" + order + "', '" + restaurant + "', 'ORD-000001', '" + UUID.randomUUID()
                        + "', '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', '" + UUID.randomUUID()
                        + "', 'SERVED', 'PAID', 'TRY', 10, 0, 10, 10, now(), 0, now(), now())");
            }

            flyway(schema, "latest").migrate();

            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet rows = statement.executeQuery("SELECT state_axis, from_status, to_status, actor_type, source"
                        + " FROM order_status_history WHERE order_id = '" + order + "' ORDER BY state_axis")) {
                    assertEquals(true, rows.next());
                    assertEquals("FULFILLMENT", rows.getString("state_axis"));
                    assertEquals(null, rows.getString("from_status"));
                    assertEquals("SERVED", rows.getString("to_status"));
                    assertEquals("SYSTEM", rows.getString("actor_type"));
                    assertEquals("BACKFILL", rows.getString("source"));
                    assertEquals(true, rows.next());
                    assertEquals("PAYMENT", rows.getString("state_axis"));
                    assertEquals("PAID", rows.getString("to_status"));
                    assertFalse(rows.next(), "exactly one row per axis");
                }
            }
        } finally {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void migrationsFromScratchEndWithTheHistoryTable() throws Exception {
        String schema = "history_scratch_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, "latest").migrate();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM order_status_history")) {
                    rows.next();
                    assertEquals(0, rows.getInt(1));
                }
            }
        } finally {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
