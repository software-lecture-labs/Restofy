package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
    public static final String PAYMENT = "PAYMENT";

    /**
     * Inbox rows that are financial facts, not message bookkeeping: a PAID that was recorded for reconciliation, and the
     * marker rows that make one real collection (capture id) unique for ever. The ordinary retention never removes them
     * (see {@code MessagingCleanupStore}); only these rows keep a redelivered financial event harmless after the
     * deduplication window of ordinary events has passed.
     */
    public static final String RECONCILIATION_EVENT_TYPE = "PaymentPaidNotApplicable";
    public static final String CAPTURE_APPLIED_TYPE = "PaymentCaptureApplied";
    public static final String CAPTURE_RECONCILED_TYPE = "PaymentCaptureReconciled";
    public static final List<String> PERMANENT_EVENT_TYPES = List.of(RECONCILIATION_EVENT_TYPE, CAPTURE_APPLIED_TYPE,
            CAPTURE_RECONCILED_TYPE);

    public record Processed(UUID orderId, String outcome, String eventType) {
    }

    private final JdbcTemplate jdbc;

    public InboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Processed> find(String source, UUID eventId) {
        return jdbc.query("SELECT order_id, outcome, event_type FROM inbox_events WHERE source = ? AND event_id = ?",
                        (rs, row) -> new Processed(rs.getObject("order_id", UUID.class), rs.getString("outcome"),
                                rs.getString("event_type")),
                        source, eventId)
                .stream().findFirst();
    }

    /**
     * Records a row only if (source, eventId) is free; false when somebody else got there first. Used for the permanent
     * capture marker, where two orders racing for the same capture id must not both succeed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean appendIfAbsent(String source, UUID eventId, UUID restaurantId, UUID orderId, String eventType,
                                  String outcome, Instant receivedAt) {
        return jdbc.update("""
                INSERT INTO inbox_events (id, source, event_id, restaurant_id, order_id, event_type, outcome, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, event_id) DO NOTHING
                """, UUID.randomUUID(), source, eventId, restaurantId, orderId, eventType, outcome,
                Timestamp.from(receivedAt)) == 1;
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
