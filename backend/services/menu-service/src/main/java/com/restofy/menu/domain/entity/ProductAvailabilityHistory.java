package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.enums.AvailabilityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable entry per availability change: previous state, new state, reason, actor and time. It
 * is written in the same transaction as the change, so re-opening a product never erases why it was
 * closed. Command ids / idempotency are intentionally not part of it yet (a later phase).
 */
@Entity
@Table(name = "product_availability_history")
public class ProductAvailabilityHistory {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 30)
    private AvailabilityStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private AvailabilityStatus toStatus;

    @Column(name = "reason_code", updatable = false, length = ProductAvailability.REASON_CODE_MAX_LENGTH)
    private String reasonCode;

    @Column(name = "reason_text", updatable = false, columnDefinition = "text")
    private String reasonText;

    @Column(name = "changed_by_user_id", nullable = false, updatable = false)
    private UUID changedByUserId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected ProductAvailabilityHistory() { }

    private ProductAvailabilityHistory(AvailabilityStatus from, ProductAvailability after) {
        this.id = UUID.randomUUID();
        this.restaurantId = after.getRestaurantId();
        this.productId = after.getProductId();
        this.fromStatus = from;
        this.toStatus = Objects.requireNonNull(after.getStatus());
        this.reasonCode = after.getReasonCode();
        this.reasonText = after.getReasonText();
        this.changedByUserId = after.getChangedByUserId();
        this.changedAt = after.getChangedAt();
    }

    /** The first entry of a product: it had no previous state. */
    public static ProductAvailabilityHistory initial(ProductAvailability created) {
        return new ProductAvailabilityHistory(null, created);
    }

    /** A change from {@code from} to the state now held by {@code after}. */
    public static ProductAvailabilityHistory changed(AvailabilityStatus from, ProductAvailability after) {
        return new ProductAvailabilityHistory(Objects.requireNonNull(from, "from"), after);
    }

    public UUID getId() { return id; }

    public UUID getRestaurantId() { return restaurantId; }

    public UUID getProductId() { return productId; }

    public AvailabilityStatus getFromStatus() { return fromStatus; }

    public AvailabilityStatus getToStatus() { return toStatus; }

    public String getReasonCode() { return reasonCode; }

    public String getReasonText() { return reasonText; }

    public UUID getChangedByUserId() { return changedByUserId; }

    public Instant getChangedAt() { return changedAt; }
}
