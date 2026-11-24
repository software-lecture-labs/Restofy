package com.restofy.waiter.infrastructure.client.order;

import java.util.List;
import java.util.UUID;

/**
 * Order request body: batch lookup of active order counts (1 to 500 sessions). It carries no restaurant: Order
 * takes the tenant from the staff session and a {@code restaurantId} in the body is a 400.
 */
public record SessionSummaryRequest(List<UUID> tableSessionIds) {
}
