package com.restofy.auth.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshToken {
    @Id private UUID id;
    @Column(nullable = false) private UUID sessionId;
    @JsonIgnore
    @Column(nullable = false, length = 64, unique = true) private String tokenHash;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant expiresAt;
    private Instant usedAt;
    private Instant revokedAt;
    @Column(nullable = false) private long generation;

    protected RefreshToken() {
    }

    public RefreshToken(UserSession session, String hash, Instant now, long generation) {
        id = UUID.randomUUID();
        sessionId = session.getId();
        tokenHash = hash;
        createdAt = now;
        expiresAt = session.getAbsoluteExpiresAt();
        this.generation = generation;
    }

    public void consume(Instant now) {
        usedAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
