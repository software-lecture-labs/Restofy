package com.restofy.auth.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

@Entity
@Table(name = "user_sessions")
@Getter
public class UserSession {
    @Id private UUID id;
    @Column(nullable = false) private UUID staffUserId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private StaffRole roleSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private SessionAuthState authState;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant lastActivityAt;
    @Column(nullable = false) private Instant absoluteExpiresAt;
    private Instant revokedAt;
    @Column(length = 100) private String deviceLabel;
    @Version private long version;

    protected UserSession() {
    }

    public UserSession(StaffUser user, Instant now, long lifetime, String deviceLabel) {
        this(user, now, lifetime, deviceLabel, true);
    }

    public UserSession(StaffUser user, Instant now, long lifetime, String deviceLabel, boolean mfaRequired) {
        id = UUID.randomUUID();
        staffUserId = user.getId();
        roleSnapshot = user.getRole();
        authState = mfaRequired && roleSnapshot == StaffRole.ADMIN
                ? SessionAuthState.MFA_PENDING : SessionAuthState.AUTHENTICATED;
        createdAt = now;
        lastActivityAt = now;
        absoluteExpiresAt = now.plusSeconds(authState == SessionAuthState.MFA_PENDING ? 300 : lifetime);
        this.deviceLabel = deviceLabel;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(absoluteExpiresAt) && now.isBefore(idleExpiresAt());
    }

    public Instant idleExpiresAt() {
        return lastActivityAt.plusSeconds(roleSnapshot == StaffRole.KITCHEN ? 3600 : 900);
    }

    public void recordActivity(Instant now) {
        if (now.isAfter(lastActivityAt)) {
            lastActivityAt = now;
        }
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public void completeMfa(Instant now, long lifetime) {
        if (roleSnapshot != StaffRole.ADMIN || authState != SessionAuthState.MFA_PENDING || !isUsable(now)) {
            throw new IllegalStateException("Invalid MFA session transition");
        }
        authState = SessionAuthState.AUTHENTICATED;
        absoluteExpiresAt = createdAt.plusSeconds(lifetime);
        lastActivityAt = now;
    }
}
