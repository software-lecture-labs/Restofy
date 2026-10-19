package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.enums.GuestSessionStatus;
import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "guest_sessions")
public class GuestSession {
    @Id
    private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;
    @Column(name = "table_session_id", nullable = false, updatable = false)
    private UUID tableSessionId;
    @Column(name = "token_digest", nullable = false, updatable = false, length = 64)
    private String tokenDigest;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GuestSessionStatus status;
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;
    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Version
    private long version;

    protected GuestSession() {
        // JPA
    }

    public GuestSession(UUID restaurantId, UUID tableSessionId, String tokenDigest, Instant joinedAt) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId);
        this.tableSessionId = Objects.requireNonNull(tableSessionId);
        if (tokenDigest == null || !tokenDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid guest digest format.");
        }
        this.tokenDigest = tokenDigest;
        this.joinedAt = IdleWindow.storedTime(Objects.requireNonNull(joinedAt));
        this.lastActivityAt = this.joinedAt;
        this.expiresAt = IdleWindow.deadline(this.joinedAt);
        this.status = GuestSessionStatus.ACTIVE;
    }

    public boolean isUsableAt(Instant now) {
        return status == GuestSessionStatus.ACTIVE && endedAt == null && !joinedAt.isAfter(now)
                && !lastActivityAt.isAfter(now) && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void recordActivityAt(Instant now) {
        if (!isUsableAt(now)) {
            throw new InvalidGuestSessionException();
        }
        lastActivityAt = IdleWindow.storedTime(now);
        expiresAt = IdleWindow.deadline(lastActivityAt);
    }

    public boolean expireIdleAt(Instant now) {
        if (status != GuestSessionStatus.ACTIVE || expiresAt == null || expiresAt.isAfter(now)) {
            return false;
        }
        expireWithMainAt(now);
        return true;
    }

    public void expireWithMainAt(Instant now) {
        if (status != GuestSessionStatus.ACTIVE) {
            return;
        }
        if (now.isBefore(lastActivityAt)) {
            throw new InvalidGuestSessionException();
        }
        status = GuestSessionStatus.EXPIRED;
        endedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableSessionId() { return tableSessionId; }
    public String getTokenDigest() { return tokenDigest; }
    public GuestSessionStatus getStatus() { return status; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getEndedAt() { return endedAt; }
    public long getVersion() { return version; }
}
