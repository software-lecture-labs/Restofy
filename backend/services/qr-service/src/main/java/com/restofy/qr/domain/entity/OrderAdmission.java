package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.enums.OrderAdmissionStatus;
import com.restofy.qr.domain.exception.OrderAdmissionConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_admissions")
public class OrderAdmission {
    @Id private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "table_session_id", nullable = false, updatable = false) private UUID tableSessionId;
    @Column(name = "guest_session_id", nullable = false, updatable = false) private UUID guestSessionId;
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 120) private String idempotencyKey;
    @Column(name = "request_digest", nullable = false, updatable = false, length = 64) private String requestDigest;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OrderAdmissionStatus status;
    @Column(name = "fencing_version", nullable = false, updatable = false) private long fencingVersion;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "rejected_at") private Instant rejectedAt;
    @Version private long version;

    protected OrderAdmission() { /* JPA */ }

    public OrderAdmission(GuestSession guest, UUID orderId, String key, String digest, Instant now, Instant deadline) {
        id = UUID.randomUUID();
        Objects.requireNonNull(guest);
        this.restaurantId = guest.getRestaurantId();
        this.tableSessionId = guest.getTableSessionId();
        this.guestSessionId = guest.getId();
        this.orderId = Objects.requireNonNull(orderId);
        validatePayload(key, digest);
        idempotencyKey = key;
        requestDigest = digest;
        requestedAt = IdleWindow.storedTime(Objects.requireNonNull(now));
        expiresAt = IdleWindow.storedTime(Objects.requireNonNull(deadline));
        if (!expiresAt.isAfter(requestedAt) || expiresAt.isAfter(requestedAt.plusSeconds(60))) {
            throw new IllegalArgumentException("Invalid admission deadline.");
        }
        // The admission ID is never reused. Version 1 fences this immutable command.
        fencingVersion = 1;
        status = OrderAdmissionStatus.PENDING;
    }

    public static void validatePayload(String key, String digest) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,120}") || digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid admission command.");
        }
    }

    public void matchCommand(UUID expectedOrderId, String digest, long fence) {
        if (!orderId.equals(expectedOrderId) || !requestDigest.equals(digest) || fencingVersion != fence) {
            throw new OrderAdmissionConflictException();
        }
    }

    public void decide(boolean accepted, long expectedVersion, Instant occurredAt, Instant now) {
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(now);
        if (occurredAt.isBefore(requestedAt) || occurredAt.isAfter(now)
                || (accepted && !occurredAt.isBefore(expiresAt)) || expectedVersion < 0) {
            throw new OrderAdmissionConflictException();
        }
        Instant stored = IdleWindow.storedTime(occurredAt);
        var target = accepted ? OrderAdmissionStatus.CONFIRMED : OrderAdmissionStatus.REJECTED;
        if (status == target && stored.equals(accepted ? confirmedAt : rejectedAt)) { return; }
        if (status != OrderAdmissionStatus.PENDING || version != expectedVersion) { throw new OrderAdmissionConflictException(); }
        status = target;
        if (accepted) { confirmedAt = stored; } else { rejectedAt = stored; }
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableSessionId() { return tableSessionId; }
    public UUID getGuestSessionId() { return guestSessionId; }
    public UUID getOrderId() { return orderId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestDigest() { return requestDigest; }
    public OrderAdmissionStatus getStatus() { return status; }
    public long getFencingVersion() { return fencingVersion; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getRejectedAt() { return rejectedAt; }
    public long getVersion() { return version; }
}
