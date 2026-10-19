package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.exception.TableSessionConflictException;
import com.restofy.qr.domain.exception.OrderOutcomeConflictException;
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
    @Column(name = "order_scope_revision", nullable = false) private long orderScopeRevision;
    @Column(name = "orders_absent_verified", nullable = false) private boolean ordersAbsentVerified;
    @Column(name = "orders_absent_verified_at") private Instant ordersAbsentVerifiedAt;

    protected TableSession() {
        // JPA
    }

    public TableSession(UUID restaurantId, UUID tableId, Instant now) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId);
        this.tableId = Objects.requireNonNull(tableId);
        this.openedAt = IdleWindow.storedTime(Objects.requireNonNull(now));
        this.lastActivityAt = openedAt;
        this.idleExpiresAt = IdleWindow.deadline(openedAt);
        this.status = TableSessionStatus.OPEN;
    }

    public boolean isOpenAt(Instant now) {
        return isOpenAt(now, false);
    }

    public boolean isOpenAt(Instant now, boolean orderProtected) {
        return status == TableSessionStatus.OPEN && closingAt == null && closedAt == null
                && !openedAt.isAfter(now) && !lastActivityAt.isAfter(now)
                && (orderProtected || idleExpiresAt == null || idleExpiresAt.isAfter(now));
    }

    public void recordActivityAt(Instant now) {
        recordActivityAt(now, false);
    }

    public void recordActivityAt(Instant now, boolean orderProtected) {
        if (!isOpenAt(now, orderProtected)) {
            throw new TableSessionConflictException();
        }
        lastActivityAt = IdleWindow.storedTime(now);
        idleExpiresAt = IdleWindow.deadline(lastActivityAt);
    }

    public boolean isIdleDueAt(Instant now) {
        return status == TableSessionStatus.OPEN && closingAt == null && closedAt == null
                && idleExpiresAt != null && !idleExpiresAt.isAfter(now);
    }

    public void invalidateOrderScope() { ordersAbsentVerified = false; ordersAbsentVerifiedAt = null; }

    public void recordOrderScope(long revision, boolean absent, Instant now) {
        if (revision <= orderScopeRevision || now.isBefore(openedAt)) { throw new OrderOutcomeConflictException(); }
        orderScopeRevision = revision; ordersAbsentVerified = absent;
        ordersAbsentVerifiedAt = absent ? IdleWindow.storedTime(now) : null;
    }

    public void restartIdleAfterCancellation(Instant cancelledAt, Instant now, boolean wasProtected) {
        if (!isOpenAt(now, wasProtected)) { return; }
        if (cancelledAt.isAfter(now) || cancelledAt.isBefore(openedAt)) { throw new OrderOutcomeConflictException(); }
        idleExpiresAt = IdleWindow.deadline(cancelledAt.isAfter(lastActivityAt) ? cancelledAt : lastActivityAt);
    }

    // Service must first establish verified NO_ORDERS under the parent lock.
    public boolean expireIdleAt(Instant now) {
        if (!isIdleDueAt(now)) {
            return false;
        }
        status = TableSessionStatus.EXPIRED;
        closedAt = now;
        return true;
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
    public long getOrderScopeRevision() { return orderScopeRevision; }
    public boolean isOrdersAbsentVerified() { return ordersAbsentVerified; }
    public boolean hasVerifiedNoOrdersAt(Instant now) {
        return ordersAbsentVerified && ordersAbsentVerifiedAt != null && !ordersAbsentVerifiedAt.isAfter(now);
    }
    public Instant getOrdersAbsentVerifiedAt() { return ordersAbsentVerifiedAt; }
}
