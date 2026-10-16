package com.restofy.order.api.dto.response;

import java.util.UUID;

/**
 * Acknowledgement of an internal event. {@code outcome}: APPLIED (the order moved), IGNORED_STALE (late or repeated,
 * nothing to do) or IGNORED_ORDER_CLOSED (the order is cancelled). {@code duplicate=true} means this exact event id
 * was processed before and {@code outcome} is the original result.
 */
public record OrderEventResponse(String outcome, boolean duplicate, UUID orderId, String fulfillmentStatus) {
}
