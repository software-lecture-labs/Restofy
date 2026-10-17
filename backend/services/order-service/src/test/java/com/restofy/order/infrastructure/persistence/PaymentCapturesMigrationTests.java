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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V49 introduces payment_captures. Runs the migrations in two steps in a throw-away schema: up to V48 (a deployment that
 * already holds PAID orders with their references in the history and reconciliation events in the outbox), then V49.
 * Proves the new tables are filled from what the provisional mechanism recorded, and that nothing old is touched.
 */
@SpringBootTest
class PaymentCapturesMigrationTests {

    @Autowired
    private DataSource dataSource;

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target).load();
    }

    private static void insertOrder(Statement statement, UUID restaurant, UUID order, String number, String paymentStatus)
            throws Exception {
        statement.execute("INSERT INTO orders (id, restaurant_id, order_no, cart_id, table_id, table_session_id,"
                + " guest_session_id, fulfillment_status, payment_status, currency, initial_net_minor, initial_vat_minor,"
                + " initial_gross_minor, current_due_minor, placed_at, version, created_at, updated_at) VALUES ('"
                + order + "', '" + restaurant + "', '" + number + "', '" + UUID.randomUUID() + "', '" + UUID.randomUUID()
                + "', '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 'RECEIVED', '" + paymentStatus
                + "', 'TRY', 9000, 0, 9000, 0, now(), 0, now(), now())");
    }

    private static void insertReconciliationEvent(Statement statement, UUID restaurant, UUID order, String reason,
                                                  UUID capture, UUID attempt, String occurredAtJson) throws Exception {
        UUID eventId = UUID.randomUUID();
        statement.execute("INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version,"
                + " event_type, schema_version, payload, occurred_at) VALUES ('" + eventId + "', '" + restaurant
                + "', 'ORDER', '" + order + "', 1, 'OrderPaymentReconciliationRequired', 1, '{\"reason\":\"" + reason
                + "\",\"reportedPaymentEventId\":\"" + UUID.randomUUID() + "\",\"reportedPaymentAttemptId\":\"" + attempt
                + "\",\"reportedCaptureId\":\"" + capture + "\",\"reportedAmountMinor\":9000,\"reportedCurrency\":\"TRY\""
                + ",\"reportedOccurredAt\":" + occurredAtJson + ",\"fulfillmentStatus\":\"CANCELLED\","
                + "\"paymentStatus\":\"NOT_REQUESTED\"}'::jsonb, now())");
    }

    @Test
    void whatTheProvisionalMechanismRecordedIsCarriedIntoTheCaptureTables() throws Exception {
        String schema = "captures_backfill_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, "48").migrate();

            UUID restaurant = UUID.randomUUID();
            UUID paidOrder = UUID.randomUUID();
            UUID cancelledOrder = UUID.randomUUID();
            UUID otherOrder = UUID.randomUUID();
            UUID capturePaid = UUID.randomUUID();
            UUID attemptPaid = UUID.randomUUID();
            UUID captureLate = UUID.randomUUID();
            UUID captureSecond = UUID.randomUUID();
            UUID paidMessage = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                insertOrder(statement, restaurant, paidOrder, "ORD-000001", "PAID");
                insertOrder(statement, restaurant, cancelledOrder, "ORD-000002", "NOT_REQUESTED");
                insertOrder(statement, restaurant, otherOrder, "ORD-000003", "PENDING");
                // the permanent PAID history row with its references (what ORDER-03 wrote)
                statement.execute("INSERT INTO order_status_history (id, restaurant_id, order_id, state_axis, from_status,"
                        + " to_status, actor_type, actor_id, actor_role, source, source_event_id, reason, occurred_at)"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + restaurant + "', '" + paidOrder + "', 'PAYMENT',"
                        + " 'PENDING', 'PAID', 'SERVICE', 'PAYMENT', 'PAYMENT', 'PAYMENT_EVENT', '" + paidMessage
                        + "', 'attemptId=" + attemptPaid + ";captureId=" + capturePaid
                        + ";amountMinor=9000;currency=TRY', now())");
                // an older PAID row without references cannot be turned into a capture and must not break anything
                statement.execute("INSERT INTO order_status_history (id, restaurant_id, order_id, state_axis, from_status,"
                        + " to_status, actor_type, source, reason, occurred_at) VALUES ('" + UUID.randomUUID() + "', '"
                        + restaurant + "', '" + otherOrder + "', 'PAYMENT', 'PENDING', 'PAID', 'SERVICE',"
                        + " 'PAYMENT_EVENT', 'amountMinor=9000;currency=TRY', now())");
                // the provisional permanent inbox marker
                statement.execute("INSERT INTO inbox_events (id, source, event_id, restaurant_id, order_id, event_type,"
                        + " outcome, received_at) VALUES ('" + UUID.randomUUID() + "', 'PAYMENT', '" + UUID.randomUUID()
                        + "', '" + restaurant + "', '" + paidOrder + "', 'PaymentCaptureApplied', 'APPLIED', now())");
                // reconciliation events: one with an ISO timestamp, one with a numeric (epoch seconds) timestamp, and one
                // where the collection already belongs to another order
                insertReconciliationEvent(statement, restaurant, cancelledOrder, "ORDER_CANCELLED", captureLate,
                        UUID.randomUUID(), "\"2026-10-16T10:15:30.123456Z\"");
                insertReconciliationEvent(statement, restaurant, paidOrder, "SECOND_CAPTURE", captureSecond,
                        UUID.randomUUID(), "1792145730.5");
                insertReconciliationEvent(statement, restaurant, otherOrder, "CAPTURE_USED_ON_OTHER_ORDER", capturePaid,
                        UUID.randomUUID(), "\"2026-10-16T11:00:00Z\"");
            }

            flyway(schema, "latest").migrate();

            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet rows = statement.executeQuery("SELECT capture_id, order_id, payment_attempt_id,"
                        + " amount_minor, currency, status, not_applied_reason, source_event_id FROM payment_captures"
                        + " ORDER BY status, capture_id")) {
                    int applied = 0;
                    int notApplied = 0;
                    while (rows.next()) {
                        if (rows.getString("status").equals("APPLIED")) {
                            applied++;
                            assertEquals(capturePaid.toString(), rows.getString("capture_id"));
                            assertEquals(paidOrder.toString(), rows.getString("order_id"));
                            assertEquals(attemptPaid.toString(), rows.getString("payment_attempt_id"));
                            assertEquals(9000, rows.getLong("amount_minor"));
                            assertEquals("TRY", rows.getString("currency"));
                            assertEquals(paidMessage.toString(), rows.getString("source_event_id"));
                        } else {
                            notApplied++;
                            assertEquals("NOT_APPLIED", rows.getString("status"));
                        }
                    }
                    assertEquals(1, applied, "the PAID row with references becomes an APPLIED capture");
                    assertEquals(2, notApplied, "the two collections that could not be applied become NOT_APPLIED");
                }
                try (ResultSet rows = statement.executeQuery("SELECT reason, order_id FROM payment_reconciliation_cases"
                        + " ORDER BY reason")) {
                    java.util.List<String> reasons = new java.util.ArrayList<>();
                    while (rows.next()) {
                        reasons.add(rows.getString("reason"));
                    }
                    assertEquals(java.util.List.of("CAPTURE_USED_ON_OTHER_ORDER", "ORDER_CANCELLED", "SECOND_CAPTURE"),
                            reasons);
                }
                // nothing old was touched
                assertEquals(2, count(statement, "order_status_history WHERE to_status = 'PAID'"));
                assertEquals(3, count(statement, "outbox_events WHERE event_type = 'OrderPaymentReconciliationRequired'"));
                assertEquals(1, count(statement, "inbox_events WHERE event_type = 'PaymentCaptureApplied'"));
            }
        } finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void aDatabaseWithoutAnyPaymentHistoryMigratesToEmptyCaptureTables() throws Exception {
        String schema = "captures_scratch_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, "latest").migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                assertEquals(0, count(statement, "payment_captures"));
                assertEquals(0, count(statement, "payment_reconciliation_cases"));
                try (ResultSet rows = statement.executeQuery("SELECT version FROM flyway_schema_history"
                        + " ORDER BY installed_rank DESC LIMIT 1")) {
                    assertTrue(rows.next());
                    assertFalse(rows.getString(1).isBlank());
                }
            }
        } finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static int count(Statement statement, String from) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + from)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
