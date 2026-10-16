package com.restofy.order.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A placed order with its immutable price snapshot. Amounts are integer minor units. */
public record OrderResponse(UUID orderId, String orderNo, UUID tableSessionId, String fulfillmentStatus,
                            String paymentStatus, String currency, Amounts amounts, List<OrderItemResponse> items,
                            String note, Instant placedAt) {

    public record Amounts(long netMinor, long vatMinor, long grossMinor) {
    }

    public record OrderItemResponse(UUID orderItemId, UUID productId, String name, int quantity,
                                    List<CartResponse.RemovedIngredient> removedIngredients,
                                    List<CartResponse.ExtraIngredient> extras, String note, long unitGrossMinor,
                                    int taxRateBps, long lineGrossMinor, long lineNetMinor, long lineVatMinor) {
    }
}
