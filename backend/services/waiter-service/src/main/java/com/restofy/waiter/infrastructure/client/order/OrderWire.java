package com.restofy.waiter.infrastructure.client.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredList;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/**
 * The Order service's real HTTP contract (PR #45, docs/order-service/entegrasyon-rehberi.md) as Waiter reads it.
 * Only the fields Waiter uses are declared and unknown fields are ignored, so Order may add fields without
 * breaking Waiter. Amounts are integer minor units. These records never leave the adapter: {@link OrderMapping}
 * turns them into Waiter's own {@link OrderDto}.
 */
final class OrderWire {

    private OrderWire() {
    }

    /** One row of a list or search; item and cancellation rows are fetched separately. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Summary(UUID orderId, String orderNo, UUID tableId, UUID tableSessionId, String fulfillmentStatus,
                   String paymentStatus, String currency, Long grossMinor, Long currentDueMinor,
                   Instant placedAt, Long version, Instant servedAt) {

        Summary {
            required(orderId, "order.orderId");
            requiredText(orderNo, "order.orderNo");
            required(tableId, "order.tableId");
            required(tableSessionId, "order.tableSessionId");
            requiredText(fulfillmentStatus, "order.fulfillmentStatus");
            required(placedAt, "order.placedAt");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(List<Summary> items, int page, int limit, Long totalItems) {

        Page {
            items = requiredList(items, "items");
            required(totalItems, "totalItems");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Amounts(long netMinor, long vatMinor, long grossMinor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Removed(UUID ingredientId, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Extra(UUID ingredientId, String name, int quantity, Long unitPriceMinor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(UUID orderItemId, UUID productId, String name, int quantity, int cancelledQuantity,
                List<Removed> removedIngredients, List<Extra> extras, String note, Long unitGrossMinor,
                Long lineGrossMinor) {

        Item {
            required(orderItemId, "item.orderItemId");
            required(productId, "item.productId");
            requiredText(name, "item.name");
            required(unitGrossMinor, "item.unitGrossMinor");
            required(lineGrossMinor, "item.lineGrossMinor");
            removedIngredients = removedIngredients == null ? List.of() : removedIngredients;
            extras = extras == null ? List.of() : extras;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Detail(UUID orderId, String orderNo, UUID tableId, UUID tableSessionId, String fulfillmentStatus,
                  String paymentStatus, String currency, Amounts amounts, Long cancelledGrossMinor,
                  Long activeGrossMinor, Long currentDueMinor, List<Item> items, Instant placedAt,
                  Instant updatedAt, Long version, Instant servedAt) {

        Detail {
            required(orderId, "order.orderId");
            requiredText(orderNo, "order.orderNo");
            required(tableId, "order.tableId");
            required(tableSessionId, "order.tableSessionId");
            requiredText(fulfillmentStatus, "order.fulfillmentStatus");
            required(placedAt, "order.placedAt");
            required(version, "order.version");
            items = requiredList(items, "order.items");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Actor(String type, String role) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelItem(UUID orderItemId, int quantity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reason(String source, String code, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cancellation(UUID cancellationId, UUID orderId, String status, List<CancelItem> items,
                        List<Reason> reasons, Instant requestedAt, Instant decidedAt, Actor requestedBy) {

        Cancellation {
            required(cancellationId, "cancellation.cancellationId");
            required(orderId, "cancellation.orderId");
            requiredText(status, "cancellation.status");
            required(requestedAt, "cancellation.requestedAt");
            items = items == null ? List.of() : items;
            reasons = reasons == null ? List.of() : reasons;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cancellations(UUID orderId, List<Cancellation> cancellations) {

        Cancellations {
            required(orderId, "orderId");
            cancellations = requiredList(cancellations, "cancellations");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionCount(UUID tableSessionId, Long activeOrderCount) {

        SessionCount {
            required(tableSessionId, "summary.tableSessionId");
            if (required(activeOrderCount, "summary.activeOrderCount") < 0) {
                throw new IllegalArgumentException("summary.activeOrderCount must not be negative");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionCounts(List<SessionCount> items) {

        SessionCounts {
            items = requiredList(items, "items");
        }
    }
}
