package com.restofy.order.application.mapper;

import com.restofy.order.api.dto.response.CartResponse;
import com.restofy.order.api.dto.response.CartResponse.CartItemResponse;
import com.restofy.order.api.dto.response.CartResponse.ExtraIngredient;
import com.restofy.order.api.dto.response.CartResponse.RemovedIngredient;
import com.restofy.order.api.dto.response.CartResponse.Totals;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.service.CartLinePricer;
import com.restofy.order.domain.entity.Cart;
import com.restofy.order.domain.entity.CartItem;
import com.restofy.order.domain.entity.CartItemOption;
import com.restofy.order.domain.enums.SelectionAction;
import com.restofy.order.domain.pricing.PriceCalculator.LinePrice;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the cart view. Prices are recomputed from the live catalog snapshot (the stored values only drive the
 * {@code priceChanged} flag). A line whose product or chosen options are no longer valid is reported as
 * unavailable with no price and is excluded from the totals, so totals always equal the sum of the priced lines.
 */
@Component
public class CartMapper {

    private final CartLinePricer pricer;

    public CartMapper(CartLinePricer pricer) {
        this.pricer = pricer;
    }

    public CartResponse toResponse(GuestSessionContext context, Optional<Cart> cart,
                                   Map<UUID, ProductSnapshot> catalog) {
        if (cart.isEmpty()) {
            return new CartResponse(null, 0L, null, context.isActive(), List.of(), new Totals(0L, 0L, 0L), true);
        }
        Cart current = cart.get();
        List<CartItemResponse> items = current.getItems().stream()
                .sorted(Comparator.comparing(CartItem::getCreatedAt).thenComparing(CartItem::getId))
                .map(item -> toItem(current, item, catalog.get(item.getProductId())))
                .toList();

        long gross = 0L;
        long net = 0L;
        long vat = 0L;
        boolean taxKnown = true;
        for (CartItemResponse item : items) {
            if (item.lineGrossMinor() == null) {
                continue;
            }
            gross = Math.addExact(gross, item.lineGrossMinor());
            if (item.lineNetMinor() == null || item.lineVatMinor() == null) {
                taxKnown = false;
            } else {
                net = Math.addExact(net, item.lineNetMinor());
                vat = Math.addExact(vat, item.lineVatMinor());
            }
        }
        Totals totals = new Totals(gross, taxKnown ? net : null, taxKnown ? vat : null);
        return new CartResponse(current.getId(), current.getVersion(), current.getCurrency(), context.isActive(),
                items, totals, true);
    }

    private CartItemResponse toItem(Cart cart, CartItem item, ProductSnapshot product) {
        List<RemovedIngredient> removed = item.getOptions().stream()
                .filter(option -> option.getAction() == SelectionAction.REMOVE)
                .sorted(Comparator.comparing(CartItemOption::getNameSnapshot).thenComparing(CartItemOption::getOptionId))
                .map(option -> new RemovedIngredient(option.getOptionId(), option.getNameSnapshot()))
                .toList();
        List<ExtraIngredient> extras = item.getOptions().stream()
                .filter(option -> option.getAction() == SelectionAction.ADD)
                .sorted(Comparator.comparing(CartItemOption::getNameSnapshot).thenComparing(CartItemOption::getOptionId))
                .map(option -> new ExtraIngredient(option.getOptionId(), option.getNameSnapshot(),
                        option.getQuantity(), option.getUnitPriceDeltaMinorSnapshot()))
                .toList();

        CartLinePricer.PricedLine priced = pricer.evaluate(cart.getCurrency(), item, product).line();
        if (priced == null) {
            return new CartItemResponse(item.getId(), item.getProductId(), item.getProductNameSnapshot(),
                    item.getQuantity(), removed, extras, item.getNote(), false, false,
                    null, null, null, null, null);
        }
        LinePrice price = priced.price();
        boolean priceChanged = pricer.differsFromSnapshot(item, priced);
        return new CartItemResponse(item.getId(), item.getProductId(), product.name(), item.getQuantity(),
                removed, extras, item.getNote(), true, priceChanged, price.unitGrossMinor(),
                price.lineGrossMinor(), product.taxRateBps(), price.lineNetMinor(), price.lineVatMinor());
    }
}
