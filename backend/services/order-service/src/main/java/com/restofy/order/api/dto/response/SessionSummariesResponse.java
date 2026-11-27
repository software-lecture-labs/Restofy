package com.restofy.order.api.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Active order counts per table session, in the order the sessions were asked. A session without any active order
 * (or one that is not the caller's restaurant's) is left out: the absence means zero. "Active" is the same set as the
 * {@code active} view of the order list: fulfillment RECEIVED, PREPARING, READY or SERVED, so neither completed nor
 * cancelled orders count. Payment is a separate axis and does not matter here.
 */
public record SessionSummariesResponse(List<Item> items) {

    public SessionSummariesResponse {
        items = ResponseCollections.copyOf(items);
    }

    public record Item(UUID tableSessionId, long activeOrderCount) {
    }
}
