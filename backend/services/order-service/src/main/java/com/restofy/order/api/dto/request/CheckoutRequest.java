package com.restofy.order.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Confirms the cart the guest saw. The cart, table and session come from the authenticated guest session; the
 * client only states the cart version and total it agreed to, and the server re-prices everything itself.
 */
public record CheckoutRequest(
        @NotNull @Min(0) Long expectedCartVersion,
        @NotNull @Min(0) Long expectedTotalMinor,
        @Size(max = 300) String note) {
}
