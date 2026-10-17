package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Product;

import java.util.Objects;

/** A product together with the place of its category in the hierarchy, as management reads it. */
public record ProductView(Product product, CategoryPlacement placement) {

    public ProductView {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(placement, "placement");
    }
}
