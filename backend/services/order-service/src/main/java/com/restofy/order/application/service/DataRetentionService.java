package com.restofy.order.application.service;

import com.restofy.order.infrastructure.config.IdempotencyProperties;
import com.restofy.order.infrastructure.persistence.CartCleanupStore;
import com.restofy.order.infrastructure.persistence.IdempotencyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Removes expired idempotency records and long-dead EXPIRED carts, one bounded batch at a time. Every run is safe to
 * repeat and to run concurrently on several instances: batches only take rows they can lock, and a second run simply
 * finds nothing left. Expiry of an idempotency record only ends the "answer an exact retry" window; it never reopens a
 * consumed cart (see {@link IdempotencyProperties}).
 */
@Slf4j
@Service
public class DataRetentionService {

    /** Upper bound of batches per run so one run cannot monopolize the database after a long outage. */
    static final int MAX_BATCHES_PER_RUN = 100;

    private final IdempotencyStore idempotency;
    private final CartCleanupStore cartCleanup;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public DataRetentionService(IdempotencyStore idempotency, CartCleanupStore cartCleanup,
                                IdempotencyProperties properties, Clock clock) {
        this.idempotency = idempotency;
        this.cartCleanup = cartCleanup;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return the number of deleted idempotency records */
    public int purgeExpiredIdempotencyRecords() {
        Instant now = clock.instant();
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = idempotency.purgeExpired(now, properties.cleanupBatchSize());
            total += deleted;
            if (deleted < properties.cleanupBatchSize()) {
                break;
            }
        }
        return total;
    }

    /** @return the number of deleted EXPIRED carts */
    public int purgeExpiredCarts() {
        Instant olderThan = clock.instant().minus(properties.expiredCartRetention());
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = cartCleanup.purgeExpiredCarts(olderThan, properties.cleanupBatchSize());
            total += deleted;
            if (deleted < properties.cleanupBatchSize()) {
                break;
            }
        }
        return total;
    }

    public void runAll() {
        int records = purgeExpiredIdempotencyRecords();
        int carts = purgeExpiredCarts();
        if (records > 0 || carts > 0) {
            log.info("Retention run finished: idempotencyRecordsDeleted={} expiredCartsDeleted={}", records, carts);
        }
    }
}
