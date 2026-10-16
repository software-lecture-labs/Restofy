package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.AddCartItemRequest;
import com.restofy.order.api.dto.request.ExtraSelectionRequest;
import com.restofy.order.api.dto.request.UpdateCartItemQuantityRequest;
import com.restofy.order.api.dto.response.CartResponse;
import com.restofy.order.application.mapper.CartMapper;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.service.CartSelectionResolver.ExtraChoice;
import com.restofy.order.application.service.ResolvedSelection.ResolvedOption;
import com.restofy.order.domain.entity.Cart;
import com.restofy.order.domain.entity.CartItem;
import com.restofy.order.domain.entity.CartItemOption;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.pricing.PriceCalculator;
import com.restofy.order.infrastructure.config.CartProperties;
import com.restofy.order.infrastructure.persistence.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cart use cases for a QR guest. Remote catalog calls happen outside DB transactions; each mutation runs in
 * one short transaction that row-locks the guest's cart, so concurrent changes to one cart are serialized.
 * All ids come from the trusted {@link GuestSessionContext}, never from the request.
 */
@Service
public class CartService {

    private final CartRepository carts;
    private final MenuCatalogPort catalog;
    private final CartSelectionResolver selections;
    private final CartMapper mapper;
    private final CartProperties limits;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public CartService(CartRepository carts, MenuCatalogPort catalog, CartSelectionResolver selections,
                       CartMapper mapper, CartProperties limits, Clock clock, PlatformTransactionManager txManager) {
        this.carts = carts;
        this.catalog = catalog;
        this.selections = selections;
        this.mapper = mapper;
        this.limits = limits;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /** Never writes: a guest without items simply gets an empty cart view. */
    public CartResponse getCurrent(GuestSessionContext context) {
        return view(context);
    }

    public CartResponse addItem(GuestSessionContext context, AddCartItemRequest request) {
        requireActiveSession(context);
        requireQuantityWithinLimit(request.quantity());

        ProductSnapshot product = catalog.findProducts(context.restaurantId(), Set.of(request.productId()))
                .get(request.productId());
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "The product does not exist");
        }
        if (!product.sellable()) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE, "The product is currently not available");
        }
        List<ExtraChoice> extras = request.extras() == null ? List.of() : request.extras().stream()
                .map((ExtraSelectionRequest extra) -> new ExtraChoice(extra.ingredientId(), extra.quantity()))
                .toList();
        ResolvedSelection selection = selections.resolve(product, request.removedIngredientIds(), extras,
                request.note());
        long unitGross;
        try {
            unitGross = PriceCalculator.unitGross(product.basePriceGrossMinor(), selection.extrasUnitDeltaMinor());
            PriceCalculator.priceLine(unitGross, request.quantity(), product.taxRateBps());
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The line amount exceeds the allowed limit");
        }

        writeTx.executeWithoutResult(status -> {
            Cart cart = lockOrCreateCart(context);
            requireExpectedVersion(cart, request.expectedCartVersion());
            Instant now = clock.instant();

            if (cart.getItems().isEmpty()) {
                cart.useCurrency(product.currency());
            } else if (!product.currency().equals(cart.getCurrency())) {
                throw new BusinessException(ErrorCode.CURRENCY_MISMATCH,
                        "The product currency does not match the cart currency");
            }

            Optional<CartItem> existing = cart.findItem(product.productId(), selection.selectionKey());
            if (existing.isPresent()) {
                CartItem item = existing.get();
                int merged = item.getQuantity() + request.quantity();
                requireQuantityWithinLimit(merged);
                item.changeQuantity(merged, now);
                item.refreshSnapshot(product.name(), unitGross, product.taxRateBps(), now);
            } else {
                if (cart.getItems().size() >= limits.maxLines()) {
                    throw new BusinessException(ErrorCode.CART_ITEM_LIMIT_EXCEEDED,
                            "The cart cannot contain more than " + limits.maxLines() + " different lines");
                }
                CartItem item = new CartItem(cart, product.productId(), request.quantity(),
                        selection.selectionKey(), selection.note(), product.name(), product.currency(),
                        unitGross, product.taxRateBps(), now);
                for (ResolvedOption option : selection.options()) {
                    item.addOption(new CartItemOption(item, option.optionId(), option.action(), option.quantity(),
                            option.name(), option.unitPriceDeltaMinor()));
                }
                cart.addItem(item);
            }
            cart.touch(now);
        });
        return view(context);
    }

    public CartResponse updateQuantity(GuestSessionContext context, UUID cartItemId,
                                       UpdateCartItemQuantityRequest request) {
        requireActiveSession(context);
        requireQuantityWithinLimit(request.quantity());

        writeTx.executeWithoutResult(status -> {
            Cart cart = lockExistingCart(context).orElseThrow(CartService::itemNotFound);
            CartItem item = cart.findItem(cartItemId).orElseThrow(CartService::itemNotFound);
            requireExpectedVersion(cart, request.expectedCartVersion());
            if (item.getQuantity() != request.quantity()) {
                Instant now = clock.instant();
                item.changeQuantity(request.quantity(), now);
                cart.touch(now);
            }
        });
        return view(context);
    }

    public CartResponse removeItem(GuestSessionContext context, UUID cartItemId, Long expectedCartVersion) {
        requireActiveSession(context);

        writeTx.executeWithoutResult(status -> {
            Cart cart = lockExistingCart(context).orElseThrow(CartService::itemNotFound);
            CartItem item = cart.findItem(cartItemId).orElseThrow(CartService::itemNotFound);
            requireExpectedVersion(cart, expectedCartVersion);
            cart.removeItem(item);
            cart.touch(clock.instant());
        });
        return view(context);
    }

    /** Idempotent: clearing an already empty (or missing) cart succeeds without changing anything. */
    public CartResponse clear(GuestSessionContext context, Long expectedCartVersion) {
        requireActiveSession(context);

        writeTx.executeWithoutResult(status ->
                lockExistingCart(context).filter(cart -> !cart.getItems().isEmpty()).ifPresent(cart -> {
                    requireExpectedVersion(cart, expectedCartVersion);
                    cart.clearItems();
                    cart.touch(clock.instant());
                }));
        return view(context);
    }

    private CartResponse view(GuestSessionContext context) {
        Optional<Cart> cart = readTx.execute(status ->
                carts.findActiveWithItems(context.restaurantId(), context.guestSessionId()));
        Map<UUID, ProductSnapshot> products = cart.filter(found -> !found.getItems().isEmpty())
                .map(found -> catalog.findProducts(context.restaurantId(), found.getItems().stream()
                        .map(CartItem::getProductId).collect(java.util.stream.Collectors.toSet())))
                .orElse(Map.of());
        return mapper.toResponse(context, cart, products);
    }

    private Cart lockOrCreateCart(GuestSessionContext context) {
        carts.insertActiveIfAbsent(UUID.randomUUID(), context.restaurantId(), context.tableId(),
                context.tableSessionId(), context.guestSessionId(), clock.instant());
        return lockExistingCart(context).orElseThrow(() ->
                new BusinessException(ErrorCode.CONFLICT, "The cart could not be opened, please retry"));
    }

    private Optional<Cart> lockExistingCart(GuestSessionContext context) {
        return carts.findActiveForUpdate(context.restaurantId(), context.guestSessionId());
    }

    private static void requireActiveSession(GuestSessionContext context) {
        if (!context.isActive()) {
            throw new BusinessException(ErrorCode.TABLE_SESSION_NOT_ACTIVE,
                    "The table session is no longer active; the cart cannot be changed");
        }
    }

    private void requireQuantityWithinLimit(int quantity) {
        if (quantity < 1 || quantity > limits.maxItemQuantity()) {
            throw new BusinessException(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                    "Quantity must be between 1 and " + limits.maxItemQuantity(),
                    Map.of("maxQuantity", limits.maxItemQuantity()));
        }
    }

    private static void requireExpectedVersion(Cart cart, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != cart.getVersion()) {
            throw new BusinessException(ErrorCode.CART_VERSION_CONFLICT,
                    "The cart was changed in the meantime; reload it and try again",
                    Map.of("currentVersion", cart.getVersion()));
        }
    }

    private static BusinessException itemNotFound() {
        return new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND, "The cart item does not exist");
    }
}
