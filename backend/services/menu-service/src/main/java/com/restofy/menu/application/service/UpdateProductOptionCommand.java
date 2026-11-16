package com.restofy.menu.application.service;

import java.math.BigDecimal;

/** Partial update of an option; a {@code null} field means "leave unchanged". */
public record UpdateProductOptionCommand(String name, BigDecimal priceDelta, Boolean removable, Integer maxQuantity,
        Integer sortOrder, Boolean active, long expectedVersion) {

    boolean changesNothing() {
        return name == null && priceDelta == null && removable == null && maxQuantity == null && sortOrder == null
                && active == null;
    }
}
