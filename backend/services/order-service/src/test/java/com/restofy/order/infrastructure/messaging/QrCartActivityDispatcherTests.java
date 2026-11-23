package com.restofy.order.infrastructure.messaging;

import com.restofy.order.domain.event.CartActivityRecordedEvent;
import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.client.QrSessionHttpAdapter;
import com.restofy.order.infrastructure.config.RestClientProperties;
import com.restofy.order.infrastructure.persistence.QrOutboxDeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@SpringBootTest
class QrCartActivityDispatcherTests {
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final Duration LEASE = Duration.ofSeconds(15);
    private static final RestClientProperties TIMEOUTS = new RestClientProperties(3000, 5000);
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxEventWriter writer;
    @Autowired private QrOutboxDeliveryStore store;
    @Autowired private PlatformTransactionManager manager;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepareDatabase() {
        transaction = new TransactionTemplate(manager);
        jdbc.update("DELETE FROM outbox_deliveries");
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void deliversOnlyCommittedCartActivityAddressedToQr() {
        UUID rolledBack = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            writer.write(event(RESTAURANT, rolledBack, CartActivityRecordedEvent.TYPE), List.of(EventDestination.QR));
            status.setRollbackOnly();
        });
        UUID deliverable = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR, EventDestination.WAITER);
        UUID otherType = recordEvent("OrderPlaced", EventDestination.QR);
        var peer = peerFor(RESTAURANT);
        when(peer.sendCartActivity(any(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(RESTAURANT, invocation.getArgument(0));
            return 204;
        });
        new QrCartActivityDispatcher(store, peer, TIMEOUTS).dispatch();
        assertEquals("DELIVERED", state(deliverable));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT status FROM outbox_deliveries WHERE event_id = ? AND destination = 'WAITER'
                """, String.class, deliverable));
        assertEquals("PENDING", state(otherType));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE id = ?", Integer.class, rolledBack));
    }

    @Test
    void retriesLostResponseAfterWorkerReplacementWithExactlyTheSameBody() {
        UUID id = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        var first = peerFor(RESTAURANT);
        byte[][] original = new byte[1][];
        when(first.sendCartActivity(any(), any())).thenAnswer(invocation -> {
            original[0] = invocation.getArgument(1);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "simulated transport timeout");
        });
        new QrCartActivityDispatcher(store, first, TIMEOUTS).dispatch();
        assertEquals("PENDING", state(id));
        assertEquals(1, attempts(id));
        assertTrue(store.claim(RESTAURANT, 5, LEASE).isEmpty(), "retry must wait for backoff");
        makeDue(id);
        var restarted = peerFor(RESTAURANT);
        when(restarted.sendCartActivity(any(), any())).thenAnswer(invocation -> {
            assertArrayEquals(original[0], invocation.getArgument(1));
            return 204;
        });
        new QrCartActivityDispatcher(store, restarted, TIMEOUTS).dispatch();
        assertEquals(2, attempts(id));
        assertEquals("DELIVERED", state(id));
    }

    @Test
    void liveLeaseExcludesOtherWorkersAndExpiredWorkerCannotOverwriteNewResult() throws Exception {
        UUID id = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> store.claim(RESTAURANT, 5, LEASE));
            var second = workers.submit(() -> store.claim(RESTAURANT, 5, LEASE));
            var claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, claims.stream().filter(Optional::isPresent).count());
            var old = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            makeDue(id);
            assertFalse(store.delivered(RESTAURANT, old), "an expired worker has no write authority");
            var replacement = store.claim(RESTAURANT, 5, LEASE).orElseThrow();
            assertEquals(old.number() + 1, replacement.number());
            assertEquals(old.eventId(), replacement.eventId());
            assertTrue(store.delivered(RESTAURANT, replacement));
            assertFalse(store.unsuccessful(RESTAURANT, old, "QR_HTTP_503", Duration.ofSeconds(5)));
            assertEquals("DELIVERED", state(id));
        }
    }

    @Test
    void skipsRowsLockedByAnotherWorker() {
        UUID locked = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        UUID free = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM outbox_deliveries WHERE event_id = ? FOR UPDATE", UUID.class, locked);
            assertEquals(free, store.claim(RESTAURANT, 5, LEASE).orElseThrow().eventId());
        });
        assertEquals(locked, store.claim(RESTAURANT, 5, LEASE).orElseThrow().eventId());
    }

    @Test
    void workersCannotClaimFinishOrExpireAnotherRestaurantsDelivery() {
        UUID other = UUID.randomUUID();
        UUID first = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        UUID second = recordEvent(other, CartActivityRecordedEvent.TYPE, EventDestination.QR);
        var otherAttempt = store.claim(other, 5, LEASE).orElseThrow();
        assertEquals(second, otherAttempt.eventId());
        assertFalse(store.delivered(RESTAURANT, otherAttempt));
        assertFalse(store.unsuccessful(RESTAURANT, otherAttempt, "QR_HTTP_409", null));
        makeDue(second);
        store.expireExhausted(RESTAURANT, 1, 8);
        assertEquals("PENDING", state(second));

        var firstPeer = peerFor(RESTAURANT);
        when(firstPeer.sendCartActivity(any(), any())).thenReturn(204);
        new QrCartActivityDispatcher(store, firstPeer, TIMEOUTS).dispatch();
        assertEquals("DELIVERED", state(first));
        assertEquals(1, attempts(second));
        var otherPeer = peerFor(other);
        when(otherPeer.sendCartActivity(any(), any())).thenReturn(204);
        new QrCartActivityDispatcher(store, otherPeer, TIMEOUTS).dispatch();
        assertEquals("DELIVERED", state(second));
        assertEquals(2, attempts(second));
    }

    @Test
    void temporaryFailuresStopAtAttemptLimitAndPermanentFailureDoesNotRetry() {
        UUID id = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        var peer = peerFor(RESTAURANT);
        when(peer.sendCartActivity(any(), any())).thenReturn(503);
        var worker = new QrCartActivityDispatcher(store, peer, TIMEOUTS);
        for (int i = 1; i <= QrCartActivityDispatcher.MAX_ATTEMPTS; i++) {
            worker.dispatch();
            assertEquals(i, attempts(id));
            if (i < QrCartActivityDispatcher.MAX_ATTEMPTS) {
                makeDue(id);
            }
        }
        worker.dispatch();
        assertEquals("FAILED", state(id));
        assertEquals(QrCartActivityDispatcher.MAX_ATTEMPTS, attempts(id));
        UUID conflict = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        when(peer.sendCartActivity(any(), any())).thenReturn(409);
        worker.dispatch();
        assertEquals("FAILED", state(conflict));
        assertEquals(1, attempts(conflict));
    }

    @Test
    void crashedFinalAttemptIsRecoveredAsUncertainWithoutExceedingTheLimit() {
        UUID id = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        for (int i = 0; i < QrCartActivityDispatcher.MAX_ATTEMPTS; i++) {
            store.claim(RESTAURANT, QrCartActivityDispatcher.MAX_ATTEMPTS, LEASE).orElseThrow();
            makeDue(id);
        }
        var peer = peerFor(RESTAURANT);
        new QrCartActivityDispatcher(store, peer, TIMEOUTS).dispatch();
        assertEquals("FAILED", state(id));
        assertEquals("QR_FINAL_ATTEMPT_UNCERTAIN", jdbc.queryForObject("""
                SELECT last_error_code FROM outbox_deliveries WHERE event_id = ?
                """, String.class, id));
        verify(peer, never()).sendCartActivity(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void recoversAfterQrConfigurationIsCorrectedWithinTheAttemptBudget(int status) {
        UUID id = recordEvent(CartActivityRecordedEvent.TYPE, EventDestination.QR);
        var peer = peerFor(RESTAURANT);
        byte[][] sent = new byte[1][];
        when(peer.sendCartActivity(any(), any())).thenAnswer(call -> {
            sent[0] = call.getArgument(1);
            return status;
        }).thenAnswer(call -> {
            assertArrayEquals(sent[0], call.getArgument(1));
            return 204;
        });
        var dispatcher = new QrCartActivityDispatcher(store, peer, TIMEOUTS);
        dispatcher.dispatch();
        assertEquals("PENDING", state(id));
        assertEquals(1, attempts(id));
        assertTrue(store.claim(RESTAURANT, 5, LEASE).isEmpty());
        makeDue(id);
        dispatcher.dispatch();
        assertEquals("DELIVERED", state(id));
        assertEquals(2, attempts(id));
    }

    private UUID recordEvent(String type, EventDestination... destinations) {
        return recordEvent(RESTAURANT, type, destinations);
    }

    private UUID recordEvent(UUID tenant, String type, EventDestination... destinations) {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> writer.write(event(tenant, id, type), List.of(destinations)));
        return id;
    }

    private static OutboxEventWriter.Event event(UUID tenant, UUID id, String type) {
        UUID cart = UUID.randomUUID();
        Instant now = Instant.now().minusSeconds(1);
        var payload = new CartActivityRecordedEvent(id, type, 1, now, null, tenant, UUID.randomUUID(),
                UUID.randomUUID(), cart, CartActivityRecordedEvent.Activity.ITEM_ADDED);
        return new OutboxEventWriter.Event(id, tenant, "CART", cart, 1, type, 1, null, payload, now);
    }

    private static QrSessionHttpAdapter peerFor(UUID tenant) {
        var peer = mock(QrSessionHttpAdapter.class);
        when(peer.restaurantId()).thenReturn(tenant);
        return peer;
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT status FROM outbox_deliveries WHERE event_id = ? AND destination = 'QR'",
                String.class, id);
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject("SELECT attempt_count FROM outbox_deliveries WHERE event_id = ?",
                Integer.class, id);
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 second' WHERE event_id = ?", id);
    }
}
