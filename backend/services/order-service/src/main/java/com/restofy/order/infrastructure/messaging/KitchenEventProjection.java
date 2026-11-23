package com.restofy.order.infrastructure.messaging;

import com.restofy.order.domain.event.OrderCancellationRequestedEvent;
import com.restofy.order.domain.event.OrderItemsCancelledEvent;
import com.restofy.order.domain.event.OrderCancelledEvent;
import com.restofy.order.domain.event.OrderPlacedEvent;
import com.restofy.order.infrastructure.persistence.KitchenOutboxDeliveryStore.Attempt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** A Kitchen-specific projection of committed outbox data. Other recipients keep their existing wire contract. */
@Component
class KitchenEventProjection {

    private final JsonMapper json;

    KitchenEventProjection(JsonMapper json) {
        this.json = json;
    }

    byte[] project(Attempt event) {
        if (event.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported Kitchen source event schema");
        }
        Object message = OrderPlacedEvent.TYPE.equals(event.eventType()) ? accepted(event) : cancellation(event);
        return json.writeValueAsBytes(message);
    }

    private Accepted accepted(Attempt event) {
        OrderPlacedEvent source = json.readValue(event.payload(), OrderPlacedEvent.class);
        List<Item> items = source.items().stream().map(item -> new Item(item.orderItemId(), item.productId(),
                item.name(), item.quantity(), item.note(), Stream.concat(
                        item.removedIngredients().stream().map(i -> new Option("REMOVE", i.ingredientId(), i.name(), 1)),
                        item.extras().stream().map(i -> new Option("ADD", i.ingredientId(), i.name(), i.quantity())))
                .toList())).toList();
        return new Accepted(event.eventId(), "OrderAccepted", event.schemaVersion(), event.occurredAt(),
                event.correlationId(), event.restaurantId(), event.orderId(), event.orderVersion(), source.orderNo(),
                source.tableId(), source.tableSessionId(), items);
    }

    private Cancellation cancellation(Attempt event) {
        JsonNode source = json.readTree(event.payload());
        List<CancelledLine> lines = source.required("items").valueStream().map(item -> new CancelledLine(
                UUID.fromString(item.required("orderItemId").asString()), item.required("quantity").asInt(),
                item.required("remainingQuantity").asInt())).toList();
        boolean requested = OrderCancellationRequestedEvent.TYPE.equals(event.eventType());
        String scope = switch (event.eventType()) {
            case OrderCancelledEvent.TYPE -> "FULL";
            case OrderItemsCancelledEvent.TYPE -> "PARTIAL";
            default -> source.required("scope").asString();
        };
        return new Cancellation(event.eventId(), event.eventType(), event.schemaVersion(), event.occurredAt(),
                event.correlationId(), event.restaurantId(), event.orderId(), event.orderVersion(),
                UUID.fromString(source.required("cancellationId").asString()),
                scope,
                requested && source.required("kitchenConfirmationRequired").asBoolean(), lines,
                requested ? null : Instant.parse(source.required("decidedAt").asString()),
                source.path("rejectionCode").asString(null));
    }

    private record Accepted(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                            String correlationId, UUID restaurantId, UUID orderId, long aggregateVersion,
                            String orderNumber, UUID tableId, UUID tableSessionId, List<Item> items) {
    }

    private record Item(UUID orderItemId, UUID productId, String productName, int quantity, String note,
                        List<Option> options) {
    }

    private record Option(String action, UUID optionId, String name, int quantity) {
    }

    private record Cancellation(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                String correlationId, UUID restaurantId, UUID orderId, long orderVersion,
                                UUID cancellationId, String scope, boolean kitchenConfirmationRequired,
                                List<CancelledLine> items, Instant decidedAt, String rejectionCode) {
    }

    private record CancelledLine(UUID orderItemId, int quantity, int remainingQuantity) {
    }
}
