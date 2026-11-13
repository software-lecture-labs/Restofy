package com.restofy.auth.application.service.revocation;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("staffRevocation")
public class RevocationReadiness implements HealthIndicator {
    private final RevocationBootstrapTransactions bootstrap;
    public RevocationReadiness(RevocationBootstrapTransactions bootstrap) { this.bootstrap=bootstrap; }
    @Override public Health health() {
        try { return bootstrap.active() ? Health.up().build() : Health.down().build(); }
        catch (RuntimeException unavailable) { return Health.down().build(); }
    }
}
