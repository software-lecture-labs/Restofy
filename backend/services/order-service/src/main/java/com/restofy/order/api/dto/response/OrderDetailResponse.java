package com.restofy.order.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The full view of one order: the immutable price snapshot, both independent statuses and what is still due.
 * {@code fulfillmentStatus} (RECEIVED...COMPLETED/CANCELLED) and {@code paymentStatus} are separate lifecycles;
 * a COMPLETED order may be unpaid and a PAID order may still be in preparation.
 */
public record OrderDetailResponse(UUID orderId, String orderNo, UUID tableId, UUID tableSessionId,
                                  String fulfillmentStatus, String paymentStatus, String currency,
                                  OrderResponse.Amounts amounts, long currentDueMinor,
                                  List<OrderResponse.OrderItemResponse> items, String note, Instant placedAt,
                                  Instant updatedAt, long version) {
}
