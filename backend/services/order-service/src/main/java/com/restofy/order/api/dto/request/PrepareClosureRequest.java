package com.restofy.order.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The QR Service asks whether a table session may be closed and, if so, to hold new orders for it. {@code operationId}
 * is the QR Service's own durable id of this closure: asking again with the same id never prepares a second closure.
 * {@code restaurantId} names the tenant the table session belongs to; the table session id is in the path.
 */
public record PrepareClosureRequest(@NotNull UUID restaurantId, @NotNull UUID operationId) {
}
