package com.restofy.qr.infrastructure.security.revocation;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("staffRevocation")
public class RevocationReadiness implements HealthIndicator {
    private final StaffRevocationStore store;
    public RevocationReadiness(StaffRevocationStore store) { this.store=store; }
    @Override public Health health() {
        try { return store.isReady() ? Health.up().build() : Health.down().build(); }
        catch (RuntimeException unavailable) { return Health.down().build(); }
    }
}
