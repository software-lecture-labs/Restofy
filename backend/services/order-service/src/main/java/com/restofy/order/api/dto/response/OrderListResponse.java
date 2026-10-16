package com.restofy.order.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of orders. Sorted by {@code placedAt} (default newest first) with the order id as tie-breaker, so paging
 * is stable. {@code view} filters on the fulfillment status only; payment is independent (see {@code paymentStatus}).
 */
public record OrderListResponse(List<Summary> items, int page, int limit, long totalItems, int totalPages,
                                String view) {

    public record Summary(UUID orderId, String orderNo, UUID tableId, UUID tableSessionId, String fulfillmentStatus,
                          String paymentStatus, String currency, long grossMinor, long currentDueMinor,
                          long unitCount, Instant placedAt) {
    }
}
