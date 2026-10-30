package com.restofy.auth.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.restofy.auth.domain.enums.PasswordResetStatus;

public record PasswordResetRequest(UUID id, UUID userId, String codeDigest, PasswordResetStatus status, String locale,
        int failures, Instant codeExpiry, Instant grantExpiry) {
    @Override
    public String toString() {
        return "Password reset state";
    }
}
