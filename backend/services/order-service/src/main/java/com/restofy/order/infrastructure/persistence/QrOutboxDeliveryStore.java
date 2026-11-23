package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
public class QrOutboxDeliveryStore {

    public record Attempt(UUID deliveryId, UUID eventId, UUID restaurantId, int number, String payload) {
    }

    private final JdbcTemplate jdbc;

    public QrOutboxDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** next_attempt_at is both the earliest retry time and the exclusive lease deadline after claiming. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Attempt> claim(UUID restaurantId, int maxAttempts, Duration lease) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT d.id, d.event_id, e.restaurant_id, e.payload
                    FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
                    WHERE d.destination = 'QR' AND e.event_type = 'CartActivityRecorded' AND e.restaurant_id = ?
                        AND d.status = 'PENDING' AND d.attempt_count < ?
                        AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= statement_timestamp())
                    ORDER BY d.next_attempt_at NULLS FIRST, d.id
                    LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                )
                UPDATE outbox_deliveries d
                SET attempt_count = d.attempt_count + 1,
                    next_attempt_at = statement_timestamp() + (? * interval '1 millisecond')
                FROM candidate c WHERE d.id = c.id
                RETURNING d.id, d.event_id, c.restaurant_id, d.attempt_count, c.payload::text AS body
                """, (rs, row) -> new Attempt(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getObject("restaurant_id", UUID.class), rs.getInt("attempt_count"),
                        rs.getString("body")), restaurantId, maxAttempts, lease.toMillis())
                .stream().findFirst();
    }

    /** A crashed worker's final attempt is uncertain. Preserve it for inspection and stop retrying. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireExhausted(UUID restaurantId, int maxAttempts, int batchSize) {
        jdbc.update("""
                WITH exhausted AS (
                    SELECT d.id FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
                    WHERE d.destination = 'QR' AND e.event_type = 'CartActivityRecorded' AND e.restaurant_id = ?
                        AND d.status = 'PENDING' AND d.attempt_count >= ?
                        AND d.next_attempt_at <= statement_timestamp()
                    ORDER BY d.next_attempt_at, d.id
                    LIMIT ? FOR UPDATE OF d SKIP LOCKED
                )
                UPDATE outbox_deliveries d
                SET status = 'FAILED', next_attempt_at = NULL, last_error_code = 'QR_FINAL_ATTEMPT_UNCERTAIN'
                FROM exhausted e WHERE d.id = e.id
                """, restaurantId, maxAttempts, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean delivered(UUID restaurantId, Attempt attempt) {
        return jdbc.update("""
                UPDATE outbox_deliveries SET status = 'DELIVERED', delivered_at = statement_timestamp(),
                    next_attempt_at = NULL, last_error_code = NULL
                FROM outbox_events e
                WHERE outbox_deliveries.id = ? AND attempt_count = ? AND status = 'PENDING'
                    AND destination = 'QR' AND e.id = event_id AND e.event_type = 'CartActivityRecorded'
                    AND e.restaurant_id = ? AND e.id = ?
                    AND next_attempt_at > statement_timestamp()
                """, attempt.deliveryId(), attempt.number(), restaurantId, attempt.eventId()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean unsuccessful(UUID restaurantId, Attempt attempt, String code, Duration retryDelay) {
        Long delay = retryDelay == null ? null : retryDelay.toMillis();
        return jdbc.update("""
                UPDATE outbox_deliveries SET status = CASE WHEN CAST(? AS bigint) IS NULL THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = statement_timestamp() + (CAST(? AS bigint) * interval '1 millisecond'),
                    last_error_code = ?
                FROM outbox_events e
                WHERE outbox_deliveries.id = ? AND attempt_count = ? AND status = 'PENDING'
                    AND destination = 'QR' AND e.id = event_id AND e.event_type = 'CartActivityRecorded'
                    AND e.restaurant_id = ? AND e.id = ?
                    AND next_attempt_at > statement_timestamp()
                """, delay, delay, code, attempt.deliveryId(), attempt.number(), restaurantId, attempt.eventId()) == 1;
    }
}
