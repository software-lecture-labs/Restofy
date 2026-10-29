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
@Table(name = "mfa_recovery_codes")
@Getter
public class MfaRecoveryCode {
    @Id private UUID id;
    @Column(nullable = false) private UUID staffUserId;
    @JsonIgnore
    @Column(nullable = false, length = 64, unique = true) private String codeDigest;
    @Column(nullable = false) private Instant createdAt;
    private Instant usedAt;
    private Instant revokedAt;

    protected MfaRecoveryCode() {

        }

    public MfaRecoveryCode(UUID userId, String digest, Instant now) {
        id = UUID.randomUUID();
        staffUserId = userId;
        codeDigest = digest;
        createdAt = now;
    }
}
