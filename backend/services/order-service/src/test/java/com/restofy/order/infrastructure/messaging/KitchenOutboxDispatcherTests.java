package com.restofy.order.infrastructure.messaging;

import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.event.OrderPlacedEvent;
import com.restofy.order.infrastructure.client.KitchenEventsHttpClient;
import com.restofy.order.infrastructure.config.KitchenRestaurantScope;
import com.restofy.order.infrastructure.config.RestClientProperties;
import com.restofy.order.infrastructure.persistence.KitchenOutboxDeliveryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class KitchenOutboxDispatcherTests {

    @Autowired private PlatformTransactionManager manager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KitchenOutboxDeliveryStore store;
    @Autowired private OutboxEventWriter writer;
    @Autowired private KitchenEventProjection projection;
    @Autowired private JsonMapper json;

    @Test
    void deliversOnlyTheConfiguredTenantAndKeepsCancellationsBehindAnUnacknowledgedAcceptance() {
        UUID tenant = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID accepted = recordPlaced(tenant, order, table, line);
        UUID cancelled = record(tenant, order, 1, "OrderCancelled", Map.of(
                "cancellationId", UUID.randomUUID(), "decidedAt", Instant.now(),
                "paymentStatus", "PAID", "refundRequiredMinor", 500,
                "items", List.of(Map.of("orderItemId", line, "quantity", 2, "remainingQuantity", 0))));
        UUID foreign = recordPlaced(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        var peer = mock(KitchenEventsHttpClient.class);
        List<byte[]> sent = new ArrayList<>();
        when(peer.send(any(), any())).thenAnswer(invocation -> {
            assertEquals(tenant, invocation.getArgument(0));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            sent.add(invocation.getArgument(1));
            return sent.size() == 1 ? 503 : 204;
        });
        var dispatcher = dispatcher(tenant, peer);
        dispatcher.dispatch();
        assertEquals(1, sent.size());
        assertEquals("PENDING", state(accepted));
        assertEquals("PENDING", state(cancelled));
        makeDue(accepted);
        dispatcher.dispatch();

        assertEquals(3, sent.size());
        assertArrayEquals(sent.get(0), sent.get(1));
        var orderMessage = json.readTree(sent.get(0));
        assertEquals(accepted.toString(), orderMessage.path("eventId").asString());
        assertEquals("OrderAccepted", orderMessage.path("eventType").asString());
        assertEquals(table.toString(), orderMessage.path("tableId").asString());
        assertFalse(orderMessage.has("tableNumber"));
        assertFalse(orderMessage.has("guestSessionId"));
        assertFalse(orderMessage.has("amounts"));
        var item = orderMessage.path("items").get(0);
        assertEquals("Salad", item.path("productName").asString());
        assertEquals("NO SALT", item.path("note").asString());
        assertFalse(item.has("unitGrossMinor"));
        assertEquals("REMOVE", item.path("options").get(0).path("action").asString());
        assertEquals(2, item.path("options").get(1).path("quantity").asInt());
        var decision = json.readTree(sent.get(2));
        assertEquals(cancelled.toString(), decision.path("eventId").asString());
        assertEquals(1, decision.path("orderVersion").asLong());
        assertEquals(0, decision.path("items").get(0).path("remainingQuantity").asInt());
        assertFalse(decision.has("paymentStatus"));
        assertFalse(decision.has("refundRequiredMinor"));
        assertEquals("DELIVERED", state(accepted));
        assertEquals("DELIVERED", state(cancelled));
        assertEquals("PENDING", state(foreign));
    }

    @Test
    void expiredWorkersCannotAcknowledgeAndFailedPredecessorsBlockOnlyTheirOwnOrder() {
        UUID tenant = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID accepted = recordPlaced(tenant, order, UUID.randomUUID(), UUID.randomUUID());
        Duration lease = Duration.ofSeconds(10);
        var first = store.claim(tenant, 5, lease).orElseThrow();
        assertEquals(accepted, first.eventId());
        assertTrue(store.claim(tenant, 5, lease).isEmpty());
        makeDue(accepted);
        var replacement = store.claim(tenant, 5, lease).orElseThrow();
        assertFalse(store.complete(first, null, null));
        assertTrue(store.complete(replacement, "KITCHEN_HTTP_422", null));
        UUID followUp = record(tenant, order, 1, "OrderCancelled", Map.of());
        UUID free = recordPlaced(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertEquals(free, store.claim(tenant, 5, lease).orElseThrow().eventId());
        assertTrue(store.claim(tenant, 5, lease).isEmpty());
        assertEquals("FAILED", state(accepted));
        assertEquals("PENDING", state(followUp));
    }

    private KitchenOutboxDispatcher dispatcher(UUID tenant, KitchenEventsHttpClient peer) {
        return new KitchenOutboxDispatcher(store, peer, projection, new KitchenRestaurantScope(tenant.toString()),
                new RestClientProperties(1000, 1000));
    }

    private UUID recordPlaced(UUID tenant, UUID order, UUID table, UUID line) {
        var item = new OrderPlacedEvent.Item(line, UUID.randomUUID(), "Salad", 2, "NO SALT",
                List.of(new OrderPlacedEvent.Ingredient(UUID.randomUUID(), "Onion")),
                List.of(new OrderPlacedEvent.Extra(UUID.randomUUID(), "Cheese", 2)), 300, 2000, 600);
        var payload = new OrderPlacedEvent(UUID.randomUUID(), OrderPlacedEvent.TYPE, 1, Instant.now(), "correlation",
                tenant, table, UUID.randomUUID(), UUID.randomUUID(), order, "A-1", "RECEIVED", "TRY",
                new OrderPlacedEvent.Amounts(500, 100, 600), null, Instant.now(), List.of(item));
        return record(tenant, order, 0, OrderPlacedEvent.TYPE, payload);
    }

    private UUID record(UUID tenant, UUID order, long version, String type, Object payload) {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(status -> writer.write(
                new OutboxEventWriter.Event(id, tenant, "ORDER", order, version, type, 1,
                        "correlation", payload, Instant.now().minusSeconds(1)),
                List.of(EventDestination.KITCHEN)));
        return id;
    }

    private void makeDue(UUID event) {
        jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = statement_timestamp() WHERE event_id = ?", event);
    }

    private String state(UUID event) {
        return jdbc.queryForObject("SELECT status FROM outbox_deliveries WHERE event_id = ?", String.class, event);
    }
}
