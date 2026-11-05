package com.restofy.auth.application.service.revocation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** PostgreSQL is the work queue. Lease expiry recovers crashed workers; duplicates remain safe. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "auth.q5.retry.enabled", havingValue = "true", matchIfMissing = true)
public class RevocationRetryWorker {
    private final RevocationTransactions transactions;
    private final RevocationCoordinator coordinator;
    private final RevocationBootstrapTransactions bootstrap;
    private final com.restofy.auth.infrastructure.client.QrRevocationClient qr;
    private final com.restofy.auth.infrastructure.config.AuthSettings settings;

    public RevocationRetryWorker(RevocationTransactions transactions, RevocationCoordinator coordinator,
            RevocationBootstrapTransactions bootstrap, com.restofy.auth.infrastructure.client.QrRevocationClient qr,
            com.restofy.auth.infrastructure.config.AuthSettings settings) {
        this.transactions = transactions;
        this.coordinator = coordinator;
        this.bootstrap = bootstrap; this.qr = qr; this.settings = settings;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void retry() {
        try {
            var challenge = qr.challenge(settings.restaurantId(), coordinator.generation());
            if (challenge == null || challenge.nonce() == null) return;
            if (!challenge.ready() || !bootstrap.active()) {
                var baseline = bootstrap.prepare(settings.restaurantId(), coordinator.generation(), challenge.nonce());
                bootstrap.finish(baseline, qr.baseline(baseline));
            }
            for (var id : transactions.claimRetries()) {
                try { coordinator.deliver(id); }
                catch (RuntimeException unavailable) { /* Durable operation remains pending. No credentials in logs. */ }
            }
        } catch (RuntimeException databaseUnavailable) { /* No claim and no finalization when the database is unavailable. */ }
    }
}
