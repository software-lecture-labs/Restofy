package com.restofy.qr.infrastructure.persistence.repository;

import com.restofy.qr.api.dto.request.OrderCartActivity;
import com.restofy.qr.domain.exception.TableSessionConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

@Repository
public class OrderCartActivityReceipts {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OrderCartActivityReceipts(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Runs in the same transaction as the session update. A failed application leaves no receipt. */
    public boolean firstDelivery(OrderCartActivity event) {
        String digest = digest(event);
        int inserted = jdbc.sql("""
                INSERT INTO order_cart_activity_receipts (event_id, payload_digest)
                VALUES (:eventId, :digest) ON CONFLICT (event_id) DO NOTHING
                """).param("eventId", event.eventId()).param("digest", digest).update();
        if (inserted == 1) { return true; }
        String stored = jdbc.sql("SELECT payload_digest FROM order_cart_activity_receipts WHERE event_id = :eventId")
                .param("eventId", event.eventId()).query(String.class).single();
        if (!digest.equals(stored)) { throw new TableSessionConflictException(); }
        return false;
    }

    /**
     * Deletes up to {@code limit} receipts accepted before {@code cutoff}, oldest first. Rows another run has locked are
     * skipped, so concurrent runs are safe and a second run simply finds nothing left.
     *
     * @return the number of deleted receipts
     */
    public int purgeReceivedBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM order_cart_activity_receipts WHERE event_id IN (
                    SELECT event_id FROM order_cart_activity_receipts WHERE received_at < :cutoff
                    ORDER BY received_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                """).param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)).param("limit", limit).update();
    }

    private String digest(OrderCartActivity event) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(event)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
