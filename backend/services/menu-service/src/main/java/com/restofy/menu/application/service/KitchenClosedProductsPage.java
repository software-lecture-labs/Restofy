package com.restofy.menu.application.service;

import java.util.List;

/** One page of closed products and the total number of closed products matching the same filter. */
public record KitchenClosedProductsPage(List<KitchenClosedProduct> items, long totalElements) {

    public KitchenClosedProductsPage {
        items = List.copyOf(items);
    }
}
