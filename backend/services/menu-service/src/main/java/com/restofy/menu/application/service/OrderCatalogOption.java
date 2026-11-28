package com.restofy.menu.application.service;

import com.restofy.menu.domain.enums.OptionType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One option of a product as Order may price it. The price difference uses the PRODUCT's currency and VAT setting.
 *
 * @param priceDelta      the stored amount, exact
 * @param priceDeltaMinor the tax-inclusive difference in minor units; {@code null} when it cannot be given exactly
 *                        (the product's price is VAT-exclusive, or not exact in the currency); a zero difference is
 *                        always {@code 0}
 * @param active          a disabled option is still listed, flagged {@code false}, so Order can tell it from a
 *                        missing one (open question for Order, request 4.3)
 */
public record OrderCatalogOption(UUID optionId, String name, OptionType type, BigDecimal priceDelta, Long priceDeltaMinor,
        int minQuantity, int maxQuantity, boolean active) {
}
