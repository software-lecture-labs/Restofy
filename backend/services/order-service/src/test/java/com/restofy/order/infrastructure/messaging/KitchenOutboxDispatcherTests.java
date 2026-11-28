package com.restofy.order.infrastructure.messaging;

import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.event.OrderCancellationRequestedEvent;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        UUID accepted = recordPlaced(tenant, order, table, line, "12");
        UUID cancelled = record(tenant, order, 1, "OrderCancelled", Map.of(
                "cancellationId", UUID.randomUUID(), "decidedAt", Instant.now(),
                "paymentStatus", "PAID", "refundRequiredMinor", 500,
                "reasons", List.of(Map.of("code", "CHANGED_MIND", "text", "private note", "source", "GUEST"),
                        Map.of("text", "text without a code")),
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
        assertEquals("12", orderMessage.path("tableNumber").asString());
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
        assertEquals(1, decision.path("reasons").size());
        assertEquals("CHANGED_MIND", decision.path("reasons").get(0).path("code").asString());
        assertEquals(1, decision.path("reasons").get(0).size());
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

    @Test
    void confirmationRequestKeepsItsKitchenContractAndExactBytesAcrossRetries() {
        UUID tenant = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID cancellation = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID event = record(tenant, order, 1, OrderCancellationRequestedEvent.TYPE, Map.of(
                "cancellationId", cancellation, "scope", "PARTIAL", "kitchenConfirmationRequired", true,
                "items", List.of(Map.of("orderItemId", line, "quantity", 1, "remainingQuantity", 2)),
                "reasons", List.of(Map.of("code", "OTHER", "text", "internal reason")), "projectedDueMinor", 250));
        var peer = mock(KitchenEventsHttpClient.class);
        List<byte[]> sent = new ArrayList<>();
        when(peer.send(any(), any())).thenAnswer(call -> {
            assertEquals(tenant, call.getArgument(0));
            sent.add(call.getArgument(1));
            return sent.size() == 1 ? 503 : 204;
        });
        var dispatcher = dispatcher(tenant, peer);
        dispatcher.dispatch();
        assertEquals("PENDING", state(event));
        makeDue(event);
        dispatcher.dispatch();

        assertEquals("DELIVERED", state(event));
        assertEquals(2, sent.size());
        assertArrayEquals(sent.get(0), sent.get(1));
        var body = json.readTree(sent.getFirst());
        assertEquals(event.toString(), body.path("eventId").asString());
        assertEquals(OrderCancellationRequestedEvent.TYPE, body.path("eventType").asString());
        assertEquals(1, body.path("schemaVersion").asInt());
        assertTrue(body.path("occurredAt").isTextual());
        assertEquals(tenant.toString(), body.path("restaurantId").asString());
        assertEquals(order.toString(), body.path("orderId").asString());
        assertEquals(cancellation.toString(), body.path("cancellationId").asString());
        assertEquals("PARTIAL", body.path("scope").asString());
        assertTrue(body.path("kitchenConfirmationRequired").asBoolean());
        assertEquals(1, body.path("items").size());
        assertEquals(line.toString(), body.path("items").get(0).path("orderItemId").asString());
        assertEquals(1, body.path("items").get(0).path("quantity").asInt());
        assertFalse(body.has("reasons"));
        assertFalse(body.has("projectedDueMinor"));
    }

    @Test
    void unsupportedPartialCancellationKeepsFullCancellationPendingWithoutBlockingOtherOrders() {
        UUID tenant = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID accepted = recordPlaced(tenant, order, UUID.randomUUID(), line);
        UUID partial = record(tenant, order, 1, "OrderItemsCancelled", Map.of(
                "cancellationId", UUID.randomUUID(), "decidedAt", Instant.now(),
                "items", List.of(Map.of("orderItemId", line, "quantity", 1, "remainingQuantity", 1))));
        UUID full = record(tenant, order, 2, "OrderCancelled", Map.of(
                "cancellationId", UUID.randomUUID(), "decidedAt", Instant.now(),
                "items", List.of(Map.of("orderItemId", line, "quantity", 1, "remainingQuantity", 0))));
        UUID independent = recordPlaced(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var peer = mock(KitchenEventsHttpClient.class);
        when(peer.send(any(), any())).thenAnswer(call -> {
            var body = json.readTree((byte[]) call.getArgument(1));
            return "OrderItemsCancelled".equals(body.path("eventType").asString()) ? 422 : 204;
        });

        dispatcher(tenant, peer).dispatch();

        assertEquals("DELIVERED", state(accepted));
        assertEquals("FAILED", state(partial));
        assertEquals("KITCHEN_HTTP_422", jdbc.queryForObject(
                "SELECT last_error_code FROM outbox_deliveries WHERE event_id = ?", String.class, partial));
        assertEquals("PENDING", state(full));
        assertEquals(0, jdbc.queryForObject(
                "SELECT attempt_count FROM outbox_deliveries WHERE event_id = ?", Integer.class, full));
        assertEquals("DELIVERED", state(independent));
    }

    @Test
    void anOperatorCanRequeueOnlyTheFailedKitchenDeliveryAndStaleAttemptsCannotCompleteIt() throws IOException {
        UUID tenant = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID accepted = recordPlaced(tenant, order, UUID.randomUUID(), UUID.randomUUID(), "12");
        UUID followUp = record(tenant, order, 1, "OrderCancelled", Map.of());
        Duration lease = Duration.ofSeconds(10);
        var stale = store.claim(tenant, 1, lease).orElseThrow();
        makeDue(accepted);
        store.expireExhausted(tenant, 1, 1);
        assertEquals("FAILED", state(accepted));
        assertTrue(store.claim(tenant, 5, lease).isEmpty());

        String requeue = Files.readString(Path.of("ops", "requeue-kitchen-delivery.sql"))
                .replace(":'restaurant_id'", "?").replace(":'event_id'", "?");
        assertTrue(jdbc.queryForList(requeue, UUID.randomUUID(), accepted).isEmpty());
        assertTrue(jdbc.queryForList(requeue, tenant, followUp).isEmpty());
        var receipt = jdbc.queryForMap(requeue, tenant, accepted);
        assertEquals(accepted, receipt.get("event_id"));
        assertEquals(1, receipt.get("previous_attempt_count"));
        assertEquals("KITCHEN_FINAL_ATTEMPT_UNCERTAIN", receipt.get("previous_error_code"));
        assertTrue(jdbc.queryForList(requeue, tenant, accepted).isEmpty());

        var retry = store.claim(tenant, 5, lease).orElseThrow();
        assertEquals(accepted, retry.eventId());
        assertEquals(stale.number(), retry.number());
        assertArrayEquals(projection.project(stale), projection.project(retry));
        assertFalse(store.complete(stale, null, null));
        assertTrue(store.claim(tenant, 5, lease).isEmpty());
        assertTrue(store.complete(retry, null, null));
        assertTrue(jdbc.queryForList(requeue, tenant, accepted).isEmpty());
        assertEquals(followUp, store.claim(tenant, 5, lease).orElseThrow().eventId());
    }

    private KitchenOutboxDispatcher dispatcher(UUID tenant, KitchenEventsHttpClient peer) {
        return new KitchenOutboxDispatcher(store, peer, projection, new KitchenRestaurantScope(tenant.toString()),
                new RestClientProperties(1000, 1000));
    }

    private UUID recordPlaced(UUID tenant, UUID order, UUID table, UUID line) {
        return recordPlaced(tenant, order, table, line, null);
    }

    private UUID recordPlaced(UUID tenant, UUID order, UUID table, UUID line, String tableNumber) {
        var item = new OrderPlacedEvent.Item(line, UUID.randomUUID(), "Salad", 2, "NO SALT",
                List.of(new OrderPlacedEvent.Ingredient(UUID.randomUUID(), "Onion")),
                List.of(new OrderPlacedEvent.Extra(UUID.randomUUID(), "Cheese", 2)), 300, 2000, 600);
        var payload = new OrderPlacedEvent(UUID.randomUUID(), OrderPlacedEvent.TYPE, 1, Instant.now(), "correlation",
                tenant, table, UUID.randomUUID(), UUID.randomUUID(), order, "A-1", "RECEIVED", "TRY",
                new OrderPlacedEvent.Amounts(500, 100, 600), null, Instant.now(), List.of(item), tableNumber);
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
