package com.restofy.qr.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Read-only view of a closure event; the database trigger writes it with the session's final transition. */
@Entity
@Immutable
@Table(name = "table_session_closure_events")
public class TableSessionClosureEvent {
    @Id @Column(name = "event_id") private UUID eventId;
    @Column(name = "event_seq", insertable = false, updatable = false) private long eventSeq;
    @Column(name = "restaurant_id") private UUID restaurantId;
    @Column(name = "table_id") private UUID tableId;
    @Column(name = "table_session_id") private UUID tableSessionId;
    @Column(name = "final_status", length = 10) private String finalStatus;
    @Column(name = "occurred_at") private Instant occurredAt;

    protected TableSessionClosureEvent() { }

    public UUID getEventId() { return eventId; }
    public long getEventSeq() { return eventSeq; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableId() { return tableId; }
    public UUID getTableSessionId() { return tableSessionId; }
    public String getFinalStatus() { return finalStatus; }
    public Instant getOccurredAt() { return occurredAt; }
}
