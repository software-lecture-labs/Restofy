package com.restofy.waiter.infrastructure.client.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.waiter.domain.enums.OrderView;

import java.util.List;
import java.util.UUID;

/**
 * Order request body of the read-only search. A missing {@code tableSessionIds} means every session; an empty
 * list is invalid and never sent. There is no restaurant in the body (a {@code restaurantId} is a 400): Order
 * takes it from the staff session. {@code view} and {@code fulfillmentStatuses} are never sent together (422):
 * a Waiter view maps to exactly one of them, see {@link #of}. {@code page} is 0-based and {@code limit} 1 to 100.
 * Order sorts by {@code placedAt} only (newest first by default).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderSearchRequest(List<UUID> tableSessionIds, UUID tableId, String view,
                                 List<String> fulfillmentStatuses, int page, int limit, String sort) {

    static OrderSearchRequest of(UUID tableId, List<UUID> tableSessionIds, OrderView view, int page, int limit) {
        String orderView = null;
        String sort = null;
        List<String> statuses = null;
        if (view != null) {
            switch (view) {
                case ACTIVE -> orderView = "active";
                case CANCELLED -> orderView = "cancelled";
                case AWAITING_DELIVERY -> {
                    statuses = List.of("READY");
                    sort = "placedAt,asc";   // the oldest waiting order first
                }
                case READY_HISTORY -> statuses = List.of("READY", "SERVED", "COMPLETED");
            }
        }
        return new OrderSearchRequest(tableSessionIds, tableId, orderView, statuses, page, limit, sort);
    }
}
