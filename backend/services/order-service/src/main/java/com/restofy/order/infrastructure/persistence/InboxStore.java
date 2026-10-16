package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers which external events were already processed, so a redelivered event is recognised. A row is written in the
 * same transaction as the change it caused ({@code MANDATORY}); if that transaction rolls back, the event is not
 * remembered and the sender may retry it.
 */
@Repository
public class InboxStore {

    public static final String KITCHEN = "KITCHEN";

    public record Processed(UUID orderId, String outcome) {
    }

    private final JdbcTemplate jdbc;

    public InboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Processed> find(String source, UUID eventId) {
        return jdbc.query("SELECT order_id, outcome FROM inbox_events WHERE source = ? AND event_id = ?",
                        (rs, row) -> new Processed(rs.getObject("order_id", UUID.class), rs.getString("outcome")),
                        source, eventId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String source, UUID eventId, UUID restaurantId, UUID orderId, String eventType, String outcome,
                       Instant receivedAt) {
        jdbc.update("""
                INSERT INTO inbox_events (id, source, event_id, restaurant_id, order_id, event_type, outcome, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), source, eventId, restaurantId, orderId, eventType, outcome,
                Timestamp.from(receivedAt));
    }
}
