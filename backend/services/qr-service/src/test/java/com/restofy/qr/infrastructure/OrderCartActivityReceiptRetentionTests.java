package com.restofy.qr.infrastructure;

import com.restofy.qr.application.service.OrderCartActivityReceiptRetentionJob;
import com.restofy.qr.infrastructure.persistence.repository.OrderCartActivityReceipts;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCartActivityReceiptRetentionTests extends TransitionIntegrationSupport {
    private static final String DIGEST = "a".repeat(64);
    @Autowired OrderCartActivityReceiptRetentionJob job;
    @Autowired OrderCartActivityReceipts receipts;

    @Test void deletesOnlyReceiptsOlderThanTheRetentionWindow() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        time.set(now);
        UUID old = receipt(now.minus(91, ChronoUnit.DAYS));
        UUID edge = receipt(now.minus(90, ChronoUnit.DAYS).plusSeconds(60));
        UUID fresh = receipt(now.minus(1, ChronoUnit.HOURS));

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(exists(edge)).isTrue();
        assertThat(exists(fresh)).isTrue();
        // A second run finds nothing left.
        assertThat(job.runOnce()).isZero();
    }

    @Test void aBatchIsBoundedAndOldestFirst() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        time.set(now);
        UUID a = receipt(now.minus(100, ChronoUnit.DAYS));
        UUID b = receipt(now.minus(99, ChronoUnit.DAYS));
        assertThat(receipts.purgeReceivedBefore(now.minus(90, ChronoUnit.DAYS), 1)).isEqualTo(1);
        assertThat(exists(a)).isFalse();
        assertThat(exists(b)).isTrue();
        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(exists(b)).isFalse();
    }

    @Test void aTooShortRetentionIsRefusedAtStartup() {
        assertThatThrownBy(() -> new OrderCartActivityReceiptRetentionJob(receipts, clock, Duration.ofDays(6)))
                .isInstanceOf(IllegalStateException.class);
    }

    private UUID receipt(Instant receivedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO order_cart_activity_receipts (event_id, payload_digest, received_at) VALUES (?,?,?)",
                id, DIGEST, Timestamp.from(receivedAt));
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM order_cart_activity_receipts WHERE event_id=?", Integer.class, id) == 1;
    }
}
