package com.restofy.order.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Housekeeping for carts that can never be used again. It only ever removes carts in the {@code EXPIRED} state: ACTIVE
 * carts are live, and CHECKED_OUT carts are the source of an order and are kept. Orders, order items, outbox events and
 * idempotency records are never touched here.
 */
@Repository
public class CartCleanupStore {

    private final JdbcTemplate jdbc;

    public CartCleanupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes up to {@code batchSize} EXPIRED carts last updated before {@code olderThan}; their items and options go
     * with them (ON DELETE CASCADE). Only EXPIRED carts are ever deleted.
     *
     * @return the number of deleted carts
     */
    @Transactional
    public int purgeExpiredCarts(Instant olderThan, int batchSize) {
        return jdbc.update("""
                DELETE FROM carts WHERE id IN (
                    SELECT c.id FROM carts c
                    WHERE c.status = 'EXPIRED' AND c.updated_at < ?
                    ORDER BY c.updated_at
                    LIMIT ?
                    FOR UPDATE OF c SKIP LOCKED)
                """, Timestamp.from(olderThan), batchSize);
    }
}
