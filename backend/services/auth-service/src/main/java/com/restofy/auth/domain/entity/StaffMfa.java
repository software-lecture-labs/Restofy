package com.restofy.auth.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.restofy.auth.domain.policy.MfaPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

@Entity
@Table(name = "staff_mfa")
@Getter
public class StaffMfa {
    @Id private UUID id;
    @Column(nullable = false, unique = true) private UUID staffUserId;
    @JsonIgnore private byte[] encryptedTotpSecret;
    private Instant enabledAt;
    private Long lastAcceptedCounter;
    private UUID pendingSessionId;
    private Instant pendingCreatedAt;
    private Instant pendingExpiresAt;
    @Column(nullable = false) private int failedAttempts;
    private Instant failureWindowStartedAt;
    private Instant lockedUntil;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private long version;

    protected StaffMfa() {

        }

    public StaffMfa(UUID userId, Instant now) {
        id = UUID.randomUUID();
        staffUserId = userId;
        createdAt = now;
        updatedAt = now;
    }

    public boolean enabled() {

        return enabledAt != null;
    }

    public void enroll(byte[] encrypted, UUID sessionId, Instant now, Instant expiry) {
        encryptedTotpSecret = encrypted.clone();
        pendingSessionId = sessionId;
        pendingCreatedAt = now;
        pendingExpiresAt = expiry;
        updatedAt = now;
    }

    public void enable(long counter, Instant now) {
        enabledAt = now;
        lastAcceptedCounter = counter;
        pendingSessionId = null;
        pendingCreatedAt = null;
        pendingExpiresAt = null;
        updatedAt = now;
    }

    public void accepted(long counter, Instant now) {
        lastAcceptedCounter = counter;
        updatedAt = now;
    }

    public void failed(Instant now) {
        if (failureWindowStartedAt == null || !now.isBefore(failureWindowStartedAt.plus(MfaPolicy.FAILURE_WINDOW))) {
            failedAttempts = 0;
            failureWindowStartedAt = now;
            lockedUntil = null;
        }
        failedAttempts = Math.min(MfaPolicy.FAILURES_BEFORE_LOCK, failedAttempts + 1);
        if (failedAttempts == MfaPolicy.FAILURES_BEFORE_LOCK) {
            lockedUntil = now.plus(MfaPolicy.LOCK_DURATION);
        }
        updatedAt = now;
    }

    public void clearFailures(Instant now) {
        failedAttempts = 0;
        failureWindowStartedAt = null;
        lockedUntil = null;
        updatedAt = now;
    }
}
