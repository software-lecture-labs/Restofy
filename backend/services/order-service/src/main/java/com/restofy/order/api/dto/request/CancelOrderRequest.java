package com.restofy.order.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Asks to cancel an order: either everything that is still active ({@code cancelAll: true}) or chosen lines and unit
 * counts ({@code items}); exactly one of the two. Prices, restaurant, table and actor are never taken from the body.
 *
 * <p>The reason is optional, always: {@code reasonCodes} (any number of the catalog's codes, none repeated) and/or a
 * short plain-text {@code reasonText}. A request without any reason is accepted; the waiter can complete it later.
 */
public record CancelOrderRequest(
        Boolean cancelAll,
        @Size(max = 50) List<@Valid Item> items,
        @Size(max = 10) List<@NotNull @Size(max = 40) String> reasonCodes,
        @Size(max = 300) String reasonText) {

    /** One order line and how many of its still-active units to cancel. */
    public record Item(@NotNull UUID orderItemId, @NotNull Integer quantity) {
    }
}
