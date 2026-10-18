package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.enums.TableSessionStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "table_sessions")
public class TableSession {
    @Id
    private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;
    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableSessionStatus status;
    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;
    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;
    @Column(name = "idle_expires_at")
    private Instant idleExpiresAt;
    @Column(name = "closing_at")
    private Instant closingAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by_user_id")
    private UUID closedByUserId;
    @Version
    private long version;

    protected TableSession() {
        // JPA
    }

    public TableSession(UUID restaurantId, UUID tableId, Instant now) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId);
        this.tableId = Objects.requireNonNull(tableId);
        this.openedAt = Objects.requireNonNull(now);
        this.lastActivityAt = now;
        this.status = TableSessionStatus.OPEN;
        // Idle calculation/refresh and closing transitions belong to QR-04.
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableId() { return tableId; }
    public TableSessionStatus getStatus() { return status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getIdleExpiresAt() { return idleExpiresAt; }
    public Instant getClosingAt() { return closingAt; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getClosedByUserId() { return closedByUserId; }
    public long getVersion() { return version; }
}
