package com.restofy.auth.domain.entity;

import java.time.Instant;
import java.util.UUID;

public record PasswordResetRequest(UUID id, UUID userId, String codeDigest, String status, String locale, int failures,
        Instant codeExpiry, Instant grantExpiry) {
    @Override public String toString() { return "Password reset state"; }
}
