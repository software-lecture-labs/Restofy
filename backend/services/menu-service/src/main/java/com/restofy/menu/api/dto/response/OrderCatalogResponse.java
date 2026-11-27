package com.restofy.menu.api.dto.response;

import com.restofy.menu.application.service.OrderCatalogOption;
import com.restofy.menu.application.service.OrderCatalogProduct;

import java.util.List;
import java.util.UUID;

/**
 * The v1 answer Order's adapter (PR #79, {@code menu-entegrasyon-rehberi.md}) expects: {@code {"products":[...]}}, minimal on
 * purpose. Absent price data is an explicit {@code null}: {@code basePriceGrossMinor} is {@code null} exactly when
 * {@code priceIssue} names why (VAT-exclusive, finer than the currency's minor unit, ...), {@code taxRateBps} is {@code null} when the VAT
 * rate is not a whole number of basis points, an option's {@code priceDeltaMinor} is {@code null} when it cannot be given
 * exactly. Nothing is rounded or guessed; Order treats such a product as not sellable.
 *
 * <p>Not sent, by decision: {@code availabilityStatus}, the stored raw price and VAT, closing reasons, descriptions and any
 * {@code priceVersion} (none is produced; Order's field is optional).
 */
public record OrderCatalogResponse(List<Product> products) {

    public static OrderCatalogResponse of(List<OrderCatalogProduct> found) {
        return new OrderCatalogResponse(found.stream().map(Product::from).toList());
    }

    public record Product(UUID productId, String name, boolean published, boolean available, String currency,
            Long basePriceGrossMinor, Integer taxRateBps, String priceIssue, List<Option> options) {

        static Product from(OrderCatalogProduct product) {
            return new Product(product.productId(), product.name(), product.published(), product.available(), product.currency(),
                    product.grossPriceMinor(), product.taxRateBps(), product.priceIssue() == null ? null : product.priceIssue().name(),
                    product.options().stream().map(Option::from).toList());
        }
    }

    public record Option(UUID optionId, String name, String type, Long priceDeltaMinor, int minQuantity, int maxQuantity,
            boolean active) {

        static Option from(OrderCatalogOption option) {
            return new Option(option.optionId(), option.name(), option.type().name(), option.priceDeltaMinor(), option.minQuantity(),
                    option.maxQuantity(), option.active());
        }
    }
}
