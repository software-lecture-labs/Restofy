package com.restofy.order.infrastructure.messaging;

import com.restofy.order.domain.event.EventDestination;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox writer. It must run inside the business transaction ({@code MANDATORY}), so an event exists
 * if and only if the change it describes was committed. It only records the event and one PENDING delivery per
 * destination; there is no dispatcher yet, so nothing has been delivered to any other service.
 */
@Slf4j
@Component
public class OutboxEventWriter {

    public record Event(UUID id, UUID restaurantId, String aggregateType, UUID aggregateId, long aggregateVersion,
                        String type, int schemaVersion, String correlationId, Object payload, Instant occurredAt) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutboxEventWriter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(Event event, List<EventDestination> destinations) {
        Timestamp occurredAt = Timestamp.from(event.occurredAt());
        jdbc.update("""
                INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version,
                                           event_type, schema_version, correlation_id, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, event.id(), event.restaurantId(), event.aggregateType(), event.aggregateId(),
                event.aggregateVersion(), event.type(), event.schemaVersion(), event.correlationId(),
                json.writeValueAsString(event.payload()), occurredAt);
        for (EventDestination destination : destinations) {
            jdbc.update("""
                    INSERT INTO outbox_deliveries (id, event_id, destination, status, attempt_count, next_attempt_at)
                    VALUES (?, ?, ?, 'PENDING', 0, ?)
                    """, UUID.randomUUID(), event.id(), destination.name(), occurredAt);
        }
        log.info("Outbox event recorded: type={} eventId={} aggregateId={} destinations={}", event.type(), event.id(),
                event.aggregateId(), destinations);
    }
}
