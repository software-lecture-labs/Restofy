package com.restofy.auth.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.domain.policy.SessionPolicy;
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
        // MFA comes first: the password is replaced only after the second factor has been proven.
        if (mfaRequired && roleSnapshot == StaffRole.ADMIN) {
            authState = SessionAuthState.MFA_PENDING;
        } else if (user.isPasswordChangeRequired()) {
            authState = SessionAuthState.PASSWORD_PENDING;
        } else {
            authState = SessionAuthState.AUTHENTICATED;
        }
        createdAt = now;
        lastActivityAt = now;
        absoluteExpiresAt = switch (authState) {
            case MFA_PENDING -> now.plus(SessionPolicy.MFA_PENDING_TTL);
            case PASSWORD_PENDING -> now.plus(SessionPolicy.PASSWORD_PENDING_TTL);
            case AUTHENTICATED -> now.plusSeconds(lifetime);
        };
        this.deviceLabel = deviceLabel;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(absoluteExpiresAt) && now.isBefore(idleExpiresAt());
    }

    public Instant idleExpiresAt() {
        return lastActivityAt.plus(roleSnapshot == StaffRole.KITCHEN
                ? SessionPolicy.KITCHEN_IDLE_TIMEOUT : SessionPolicy.IDLE_TIMEOUT);
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

    /**
     * Passing MFA makes the session usable, unless the password was set by an ADMIN: then the session moves on to
     * {@link SessionAuthState#PASSWORD_PENDING} with a fresh short deadline.
     */
    public void completeMfa(Instant now, long lifetime, boolean passwordChangeRequired) {
        if (roleSnapshot != StaffRole.ADMIN || authState != SessionAuthState.MFA_PENDING || !isUsable(now)) {
            throw new IllegalStateException("Invalid MFA session transition");
        }
        if (passwordChangeRequired) {
            authState = SessionAuthState.PASSWORD_PENDING;
            absoluteExpiresAt = now.plus(SessionPolicy.PASSWORD_PENDING_TTL);
        } else {
            authState = SessionAuthState.AUTHENTICATED;
            absoluteExpiresAt = createdAt.plusSeconds(lifetime);
        }
        lastActivityAt = now;
    }

    /** The member replaced an ADMIN-set password; the session gets its normal lifetime from its creation. */
    public void completePasswordChange(Instant now, long lifetime) {
        if (authState != SessionAuthState.PASSWORD_PENDING || !isUsable(now)) {
            throw new IllegalStateException("Invalid password session transition");
        }
        authState = SessionAuthState.AUTHENTICATED;
        absoluteExpiresAt = createdAt.plusSeconds(lifetime);
        lastActivityAt = now;
    }
}
