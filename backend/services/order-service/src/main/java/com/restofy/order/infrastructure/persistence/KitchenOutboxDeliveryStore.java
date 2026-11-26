package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class KitchenOutboxDeliveryStore {

    private static final String TYPES = """
            ('OrderPlaced', 'OrderCancellationRequested', 'OrderCancelled',
             'OrderItemsCancelled', 'OrderCancellationRejected')
            """;

    public record Attempt(UUID deliveryId, UUID eventId, UUID restaurantId, UUID orderId, long orderVersion,
                          String eventType, int schemaVersion, String correlationId, Instant occurredAt,
                          int number, Instant leaseUntil, String payload) {
    }

    private final JdbcTemplate jdbc;

    public KitchenOutboxDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A pending or failed predecessor blocks delivery for this order, while other orders can proceed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Attempt> claim(UUID restaurantId, int maxAttempts, Duration lease) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT d.id, e.id AS event_id, e.restaurant_id, e.aggregate_id, e.aggregate_version,
                        e.event_type, e.schema_version, e.correlation_id, e.occurred_at, e.payload
                    FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
                    WHERE d.destination = 'KITCHEN' AND e.aggregate_type = 'ORDER'
                        AND e.event_type IN %s AND e.restaurant_id = ?
                        AND d.status = 'PENDING' AND d.attempt_count < ?
                        AND d.next_attempt_at <= statement_timestamp()
                        AND NOT EXISTS (
                            SELECT 1 FROM outbox_deliveries p JOIN outbox_events prior ON prior.id = p.event_id
                            WHERE p.destination = 'KITCHEN' AND p.status <> 'DELIVERED' AND prior.event_type IN %s
                                AND prior.restaurant_id = e.restaurant_id AND prior.aggregate_id = e.aggregate_id
                                AND prior.aggregate_type = e.aggregate_type
                                AND (prior.aggregate_version, prior.occurred_at,
                                     CASE prior.event_type WHEN 'OrderPlaced' THEN 0
                                                          WHEN 'OrderCancellationRequested' THEN 1 ELSE 2 END, prior.id)
                                    < (e.aggregate_version, e.occurred_at,
                                       CASE e.event_type WHEN 'OrderPlaced' THEN 0
                                                         WHEN 'OrderCancellationRequested' THEN 1 ELSE 2 END, e.id)
                        )
                    ORDER BY d.next_attempt_at, d.id
                    LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                )
                UPDATE outbox_deliveries d
                SET attempt_count = d.attempt_count + 1,
                    next_attempt_at = statement_timestamp() + (? * interval '1 millisecond')
                FROM candidate c WHERE d.id = c.id
                RETURNING d.id, c.event_id, c.restaurant_id, c.aggregate_id, c.aggregate_version, c.event_type,
                    c.schema_version, c.correlation_id, c.occurred_at, d.attempt_count, d.next_attempt_at,
                    c.payload::text AS body
                """.formatted(TYPES, TYPES), (rs, row) -> new Attempt(rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getObject("restaurant_id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class), rs.getLong("aggregate_version"),
                        rs.getString("event_type"), rs.getInt("schema_version"), rs.getString("correlation_id"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getInt("attempt_count"),
                        rs.getTimestamp("next_attempt_at").toInstant(), rs.getString("body")),
                restaurantId, maxAttempts, lease.toMillis()).stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireExhausted(UUID restaurantId, int maxAttempts, int limit) {
        jdbc.update("""
                WITH exhausted AS (
                    SELECT d.id FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
                    WHERE d.destination = 'KITCHEN' AND e.aggregate_type = 'ORDER'
                        AND e.event_type IN %s AND e.restaurant_id = ?
                        AND d.status = 'PENDING' AND d.attempt_count >= ? AND d.next_attempt_at <= statement_timestamp()
                    ORDER BY d.next_attempt_at, d.id LIMIT ? FOR UPDATE OF d SKIP LOCKED
                )
                UPDATE outbox_deliveries d SET status = 'FAILED', next_attempt_at = NULL,
                    last_error_code = 'KITCHEN_FINAL_ATTEMPT_UNCERTAIN'
                FROM exhausted e WHERE d.id = e.id
                """.formatted(TYPES), restaurantId, maxAttempts, limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(Attempt attempt, String error, Duration retryDelay) {
        boolean delivered = error == null;
        String status = delivered ? "DELIVERED" : retryDelay == null ? "FAILED" : "PENDING";
        Long delay = retryDelay == null ? null : retryDelay.toMillis();
        return jdbc.update("""
                UPDATE outbox_deliveries d SET status = ?, last_error_code = ?,
                    delivered_at = CASE WHEN ? THEN statement_timestamp() ELSE NULL END,
                    next_attempt_at = statement_timestamp() + (CAST(? AS bigint) * interval '1 millisecond')
                FROM outbox_events e
                WHERE d.id = ? AND d.event_id = ? AND d.attempt_count = ? AND d.status = 'PENDING'
                    AND d.destination = 'KITCHEN' AND e.id = d.event_id AND e.restaurant_id = ?
                    AND d.next_attempt_at = ? AND d.next_attempt_at > statement_timestamp()
                """, status, error, delivered, delay, attempt.deliveryId(), attempt.eventId(), attempt.number(),
                attempt.restaurantId(), Timestamp.from(attempt.leaseUntil())) == 1;
    }
}
