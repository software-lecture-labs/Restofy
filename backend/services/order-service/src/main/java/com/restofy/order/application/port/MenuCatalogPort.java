package com.restofy.order.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Contract with the Menu Service (product/price/tax authority). Results are scoped to the given restaurant:
 * a product of another restaurant is simply absent from the result.
 *
 * <p>Prices in {@link ProductSnapshot} are tax-inclusive gross amounts in integer minor units. A known product
 * with no exact gross price (e.g. a net-priced product) has no base price and cannot be sold.
 */
public interface MenuCatalogPort {

    /** Returns the known products among {@code productIds}; unknown ids are omitted. */
    Map<UUID, ProductSnapshot> findProducts(UUID restaurantId, Collection<UUID> productIds);
}
