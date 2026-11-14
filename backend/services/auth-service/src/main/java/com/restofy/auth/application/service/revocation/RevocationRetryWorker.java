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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RevocationRetryWorker.class);
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
        String stage = "SOURCE_IDENTITY";
        try {
            bootstrap.verifyConfiguredIdentity();
            stage = "CHALLENGE";
            refreshPeer();
            stage = "CLAIM";
            var queue = transactions.queueStats();
            if (queue.pending() > 0) {
                log.info("Q5 queue pending={} oldest_seconds={}", queue.pending(), queue.oldestSeconds());
            }
            for (var id : transactions.claimRetries()) {
                try { coordinator.deliver(id); }
                catch (RuntimeException unavailable) { log.warn("Q5 retry deferred operation={} stage=DELIVERY category={}", id, category(unavailable)); }
            }
        } catch (RuntimeException unavailable) { log.warn("Q5 retry deferred stage={} category={}", stage, category(unavailable)); }
    }

    private void refreshPeer() {
        com.restofy.auth.infrastructure.client.QrRevocationClient.Challenge challenge;
        try {
            challenge = qr.challenge(settings.restaurantId(), coordinator.generation());
            if (challenge == null || challenge.nonce() == null) {
                throw new IllegalStateException("QR challenge unavailable");
            }
        } catch (RuntimeException unavailable) {
            // Only a durable, matching ACTIVE source may process its existing queue offline.
            // Every queue/finalize transaction independently rechecks the source barrier.
            if (!bootstrap.active()) throw unavailable;
            log.warn("Q5 challenge deferred category={}", category(unavailable));
            return;
        }
        if (!challenge.ready() || !bootstrap.active()) {
            var baseline = bootstrap.prepare(settings.restaurantId(), coordinator.generation(), challenge.nonce());
            bootstrap.finish(baseline, qr.baseline(baseline));
        }
    }

    private static String category(RuntimeException failure) {
        if (failure instanceof org.springframework.dao.DataAccessException) return "DATABASE";
        if (failure instanceof org.springframework.web.server.ResponseStatusException response) return "HTTP_" + response.getStatusCode().value();
        return "TRANSPORT_OR_PROTOCOL";
    }
}
