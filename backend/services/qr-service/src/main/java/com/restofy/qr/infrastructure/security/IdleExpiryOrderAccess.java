package com.restofy.qr.infrastructure.security;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IdleExpiryOrderAccess {
    public enum Knowledge { UNKNOWN, NO_ORDERS, HAS_ORDERS }

    // Integration boundary, deliberately fail closed. Future implementation must
    // use authoritative locally coordinated state; do not call a remote service
    // while holding the parent lock or interpret an unavailable service as empty.
    public Knowledge knowledge(UUID restaurantId, UUID tableSessionId) {
        Objects.requireNonNull(restaurantId);
        Objects.requireNonNull(tableSessionId);
        return Knowledge.UNKNOWN;
    }
}
