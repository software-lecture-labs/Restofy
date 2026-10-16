package com.restofy.order.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * The guest's current cart. Amounts are integer minor units and are ESTIMATES: the binding amounts are
 * fixed server-side at checkout. {@code cartId} is null while the guest has not added anything yet.
 * {@code totals.netMinor/vatMinor} are null unless every priced line has a known tax rate.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CartResponse(UUID cartId, long version, String currency, boolean sessionActive,
                           List<CartItemResponse> items, Totals totals, boolean estimate) {

    public record Totals(long grossMinor, Long netMinor, Long vatMinor) {
    }

    public record CartItemResponse(UUID cartItemId, UUID productId, String name, int quantity,
                                   List<RemovedIngredient> removedIngredients, List<ExtraIngredient> extras,
                                   String note, boolean available, boolean priceChanged,
                                   Long unitGrossMinor, Long lineGrossMinor, Integer taxRateBps,
                                   Long lineNetMinor, Long lineVatMinor) {
    }

    public record RemovedIngredient(UUID ingredientId, String name) {
    }

    public record ExtraIngredient(UUID ingredientId, String name, int quantity, long unitPriceMinor) {
    }
}
