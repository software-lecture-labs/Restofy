package com.restofy.menu.application.service;

import com.restofy.menu.application.security.TrustedOrderCaller;
import com.restofy.menu.application.security.TrustedOrderCallerProvider;
import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.domain.entity.ProductOption;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.ForbiddenOperationException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductOptionRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Answers the Order service's question "what is the current state of these products?" (request ORDER-REQUEST-01). Menu is
 * the only authority for product, price, VAT, options and availability; this is a read-only view of them, never a
 * reservation or an order.
 *
 * <p>There is no HTTP entry point yet and the production caller provider is fail-closed, so in production every lookup
 * is refused until a verified service identity exists (Auth D-09, open). Tests use a test-only trusted caller.
 *
 * <ul>
 *   <li>The caller is verified first; then the request is validated; then the restaurant must be the caller's own.</li>
 *   <li>Every query carries the restaurant id. An unknown id and an id of another restaurant are the same thing: absent
 *       from the answer, no error, so nothing about other restaurants can be learned.</li>
 *   <li>Archived, draft and closed products ARE returned, flagged ({@code published}/{@code available}) so that they are
 *       never orderable, rather than omitted. Whether archived ones should be omitted instead is an open question for
 *       Order; it changes no safety property, because both are non-orderable.</li>
 *   <li>One repeatable-read transaction: all products of one answer come from the same database snapshot, so a price
 *       change cannot appear half-applied inside one answer. It is still a point in time: it is not a reservation.</li>
 *   <li>The answer is built from the current rows on every call. Nothing is cached, so a call right after a price or
 *       availability change returns the new state.</li>
 * </ul>
 */
@Service
public class OrderCatalogService {

    /** Provisional: Order's cart holds at most 50 lines. The final limit is part of the open contract. */
    public static final int MAX_PRODUCT_IDS = 50;

    private static final Comparator<ProductOption> OPTION_ORDER = Comparator.comparingInt(ProductOption::getSortOrder)
            .thenComparing(option -> option.getName().toLowerCase(java.util.Locale.ROOT))
            .thenComparing(ProductOption::getId);

    private final TrustedOrderCallerProvider callers;
    private final ProductRepository products;
    private final ProductAvailabilityRepository availabilities;
    private final ProductOptionRepository options;
    private final CategoryPlacementResolver placements;

    public OrderCatalogService(TrustedOrderCallerProvider callers, ProductRepository products,
            ProductAvailabilityRepository availabilities, ProductOptionRepository options,
            CategoryPlacementResolver placements) {
        this.callers = callers;
        this.products = products;
        this.availabilities = availabilities;
        this.options = options;
        this.placements = placements;
    }

    /**
     * @return the known products among {@code productIds} in the order they were asked for (first occurrence); unknown
     *         ids and ids of other restaurants are omitted
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<OrderCatalogProduct> lookup(UUID restaurantId, Collection<UUID> productIds) {
        TrustedOrderCaller caller = callers.current().orElseThrow(UnauthenticatedException::new);
        if (restaurantId == null) {
            throw new InvalidProductException("A restaurant id is required.");
        }
        if (!caller.restaurantId().equals(restaurantId)) {
            throw new ForbiddenOperationException();
        }
        Set<UUID> ids = validated(productIds);

        Map<UUID, Product> found = new HashMap<>();
        products.findByRestaurantIdAndIdIn(restaurantId, ids).forEach(product -> found.put(product.getId(), product));
        if (found.isEmpty()) {
            return List.of();
        }
        Set<UUID> foundIds = found.keySet();
        Map<UUID, ProductAvailability> availability = new HashMap<>();
        availabilities.findByRestaurantIdAndProductIdIn(restaurantId, foundIds)
                .forEach(row -> availability.put(row.getProductId(), row));
        Map<UUID, CategoryPlacement> placement = placements.resolveAll(restaurantId,
                found.values().stream().map(Product::getCategoryId).collect(java.util.stream.Collectors.toSet()));
        Map<UUID, List<ProductOption>> optionsByProduct = new HashMap<>();
        options.findByRestaurantIdAndProductIdIn(restaurantId, foundIds)
                .forEach(option -> optionsByProduct.computeIfAbsent(option.getProductId(), id -> new ArrayList<>()).add(option));

        List<OrderCatalogProduct> answer = new ArrayList<>();
        for (UUID id : ids) {
            Product product = found.get(id);
            if (product != null) {
                answer.add(view(product, availability.get(id), placement.get(product.getCategoryId()),
                        optionsByProduct.getOrDefault(id, List.of())));
            }
        }
        return answer;
    }

    private static Set<UUID> validated(Collection<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            throw new InvalidProductException("At least one product id is required.");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (UUID id : productIds) {
            if (id == null) {
                throw new InvalidProductException("A product id must not be null.");
            }
            ids.add(id);
        }
        if (ids.size() > MAX_PRODUCT_IDS) {
            throw new InvalidProductException("At most " + MAX_PRODUCT_IDS + " distinct product ids are allowed.");
        }
        return ids;
    }

    private static OrderCatalogProduct view(Product product, ProductAvailability availability, CategoryPlacement placement,
            List<ProductOption> productOptions) {
        AvailabilityStatus status = availability == null ? null : availability.getStatus();
        // A product whose category cannot be resolved cannot be shown: fail closed.
        boolean published = placement != null && ProductEligibility.of(product, placement, status).isVisibleToCustomer();

        CatalogMoney.Conversion price = product.isPriceIncludesVat()
                ? CatalogMoney.toMinor(product.getBasePrice(), product.getCurrency())
                : CatalogMoney.Conversion.refused(PriceIssue.VAT_EXCLUSIVE);

        List<OrderCatalogOption> optionViews = productOptions.stream().sorted(OPTION_ORDER)
                .map(option -> optionView(product, option)).toList();

        return new OrderCatalogProduct(product.getId(), product.getName(), product.getCurrency(), product.getBasePrice(),
                product.isPriceIncludesVat(), product.getVatRate(), price.minor(), CatalogMoney.toBasisPoints(product.getVatRate()),
                price.issue(), published, status == AvailabilityStatus.AVAILABLE, status, optionViews);
    }

    private static OrderCatalogOption optionView(Product product, ProductOption option) {
        Long minor;
        if (option.getPriceDelta().signum() == 0) {
            minor = 0L;
        } else if (!product.isPriceIncludesVat()) {
            minor = null;
        } else {
            minor = CatalogMoney.toMinor(option.getPriceDelta(), product.getCurrency()).minor();
        }
        return new OrderCatalogOption(option.getId(), option.getName(), option.getType(), option.getPriceDelta(), minor,
                option.getMinQuantity(), option.getMaxQuantity(), option.isActive());
    }
}
