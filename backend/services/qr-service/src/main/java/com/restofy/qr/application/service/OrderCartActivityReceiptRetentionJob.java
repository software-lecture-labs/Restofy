package com.restofy.qr.application.service;

import com.restofy.qr.infrastructure.persistence.repository.OrderCartActivityReceipts;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes old Order cart-activity receipts, one bounded batch at a time. A receipt only recognises a redelivered
 * event, so it is kept well beyond Order's delivery retries and its delivered-outbox retention (30 days).
 * Every run is safe to repeat and to run on several instances at once.
 */
@Component
public class OrderCartActivityReceiptRetentionJob {
    private static final Logger LOG = LoggerFactory.getLogger(OrderCartActivityReceiptRetentionJob.class);
    static final Duration MINIMUM_RETENTION = Duration.ofDays(7);
    static final int BATCH_SIZE = 1000;
    /** Upper bound of batches per run so one run cannot monopolize the database after a long pause. */
    static final int MAX_BATCHES_PER_RUN = 100;
    private static final long SWEEP_DELAY_MILLIS = 3_600_000;
    private final OrderCartActivityReceipts receipts;
    private final Clock clock;
    private final Duration retention;

    public OrderCartActivityReceiptRetentionJob(OrderCartActivityReceipts receipts, Clock clock,
                                                @Value("${qr.cart-receipt-retention:P90D}") Duration retention) {
        if (retention.compareTo(MINIMUM_RETENTION) < 0) {
            throw new IllegalStateException("qr.cart-receipt-retention must be at least " + MINIMUM_RETENTION);
        }
        this.receipts = receipts;
        this.clock = clock;
        this.retention = retention;
    }

    /** @return the number of deleted receipts */
    @Scheduled(initialDelay = SWEEP_DELAY_MILLIS, fixedDelay = SWEEP_DELAY_MILLIS)
    public synchronized int runOnce() {
        var cutoff = clock.instant().minus(retention);
        int total = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                int deleted = receipts.purgeReceivedBefore(cutoff, BATCH_SIZE);
                total += deleted;
                if (deleted < BATCH_SIZE) { break; }
            }
        } catch (RuntimeException exception) {
            // Do not log SQL or private exception messages; a later run continues where this one stopped.
            LOG.warn("Cart receipt retention failed with {}; a later run will retry safely.", exception.getClass().getSimpleName());
        }
        LOG.debug("Cart receipt retention deleted={}", total);
        return total;
    }
}
