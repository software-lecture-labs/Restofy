package com.restofy.menu.application.service;

import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import com.restofy.menu.domain.entity.ProductOption;
import com.restofy.menu.domain.entity.ProductSearchTerm;
import com.restofy.menu.domain.enums.OptionType;
import com.restofy.menu.domain.exception.CategoryNotFoundException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.PublicMenuNotFoundException;
import com.restofy.menu.infrastructure.persistence.repository.ProductOptionRepository;
import com.restofy.menu.infrastructure.persistence.repository.PublicProductSearchRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The read-only customer menu. The restaurant is never taken from the request: the reference in the URL goes
 * through the {@link PublicRestaurantScopeResolver}, and only a restaurant it verified is read (the fail-closed
 * default verifies none). Anything that cannot be shown to this customer, whatever the reason, is the same
 * {@link PublicMenuNotFoundException}. Nothing here writes, orders or grants any authority.
 *
 * <p><b>What a read guarantees.</b> Each public read runs in one {@code REPEATABLE READ} transaction, so the page, the
 * total and the categories and availability loaded for it all come from the same PostgreSQL snapshot: a card never
 * disagrees with the list it is in, and the total counts exactly the products that could be listed. It is a snapshot
 * of the moment the read started, not a live view: a change committed a moment later shows on the next read.
 * "Orderable" here is a display fact only. Nothing is reserved or locked, so the order flow must check the
 * availability again when it places an order.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublicMenuService {

    private static final int MAX_REFERENCE_LENGTH = 128;

    private final PublicRestaurantScopeResolver scope;
    private final ActiveCategoryTreeReader categoryTree;
    private final CategoryPlacementResolver placements;
    private final PublicProductSearchRepository search;
    private final ProductViewAssembler views;
    private final ProductOptionRepository options;

    public PublicMenuService(PublicRestaurantScopeResolver scope, ActiveCategoryTreeReader categoryTree,
            CategoryPlacementResolver placements, PublicProductSearchRepository search, ProductViewAssembler views,
            ProductOptionRepository options) {
        this.options = options;
        this.scope = scope;
        this.categoryTree = categoryTree;
        this.placements = placements;
        this.search = search;
        this.views = views;
    }

    public List<ActiveCategoryNode> categories(String restaurantRef) {
        return categoryTree.activeTree(verifiedRestaurant(restaurantRef));
    }

    /** A product card with its short description, ready for the mapper. */
    public record Card(ProductView view, String summary) { }

    public Page<Card> products(String restaurantRef, PublicProductQuery query, PublicProductSort sort, int page, int size) {
        UUID restaurantId = verifiedRestaurant(restaurantRef);
        String term = ProductSearchTerm.normalize(query.q());

        CategoryPlacement main = visibleCategory(restaurantId, query.categoryId());
        CategoryPlacement sub = visibleCategory(restaurantId, query.subcategoryId());
        if (main != null && main.isSubcategory()) {
            throw new InvalidProductException("categoryId must be a main category.");
        }
        if (sub != null && !sub.isSubcategory()) {
            throw new InvalidProductException("subcategoryId must be a subcategory.");
        }
        if (main != null && sub != null && !sub.parent().getId().equals(main.category().getId())) {
            throw new InvalidProductException("subcategoryId does not belong to categoryId.");
        }

        var result = search.search(new PublicProductSearchRepository.Scope(restaurantId, query.categoryId(),
                query.subcategoryId(), term), sort, page, size);
        List<Card> cards = views.assembleAll(restaurantId, result.items()).stream()
                .filter(view -> view.eligibility().isListedPublicly()) // fail closed if SQL and the rule ever drift apart
                .map(PublicMenuService::card)
                .toList();
        return new PageImpl<>(cards, PageRequest.of(page, size), result.total());
    }

    /**
     * One product by id under exactly the conditions of the list, for the product detail of a later task. A hidden
     * product, an unknown id and the product of another restaurant all end in the same
     * {@link PublicMenuNotFoundException}, like an unverified restaurant reference.
     */
    public Card listedProduct(String restaurantRef, UUID productId) {
        UUID restaurantId = verifiedRestaurant(restaurantRef);
        if (productId == null) {
            throw new PublicMenuNotFoundException();
        }
        return search.findListed(restaurantId, productId)
                .map(views::assemble)
                .filter(view -> view.eligibility().isListedPublicly())
                .map(PublicMenuService::card)
                .orElseThrow(PublicMenuNotFoundException::new);
    }

    /** A listed product with its active options, read in one snapshot. */
    public record ListedDetail(ProductView view, List<ProductOption> includedIngredients, List<ProductOption> extras) { }

    /**
     * The product detail: {@link #listedProduct} (same visibility, same single "not found") plus the product's active
     * options from the same transaction, one statement for all of them. Only active options exist for a customer, of
     * this product and this restaurant. They are ordered by sort order, then name in Turkish alphabetical order, then id,
     * and split by kind. They stay visible when the product is closed (it is then simply not orderable).
     */
    public ListedDetail listedDetail(String restaurantRef, UUID productId) {
        ProductView view = listedProduct(restaurantRef, productId).view();
        List<ProductOption> active = options.findByRestaurantIdAndProductIdAndActiveTrue(
                view.product().getRestaurantId(), view.product().getId());
        Collator turkish = Collator.getInstance(Locale.forLanguageTag("tr-TR"));
        Comparator<ProductOption> order = Comparator.comparingInt(ProductOption::getSortOrder)
                .thenComparing(ProductOption::getName, turkish).thenComparing(ProductOption::getId);
        return new ListedDetail(view, ofType(active, OptionType.INCLUDED, order), ofType(active, OptionType.ADD, order));
    }

    private static List<ProductOption> ofType(List<ProductOption> all, OptionType type, Comparator<ProductOption> order) {
        return all.stream().filter(option -> option.getType() == type).sorted(order).toList();
    }

    private static Card card(ProductView view) {
        return new Card(view, ProductSummary.of(view.product().getDescription()));
    }

    private UUID verifiedRestaurant(String restaurantRef) {
        if (restaurantRef == null || restaurantRef.isBlank() || restaurantRef.length() > MAX_REFERENCE_LENGTH) {
            throw new PublicMenuNotFoundException();
        }
        return scope.resolve(restaurantRef).orElseThrow(PublicMenuNotFoundException::new);
    }

    /**
     * {@code null} for no filter. A category that does not exist in this restaurant, belongs to another one or is
     * not effectively active is the same "not found".
     */
    private CategoryPlacement visibleCategory(UUID restaurantId, UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        CategoryPlacement placement;
        try {
            placement = placements.resolve(restaurantId, categoryId);
        } catch (CategoryNotFoundException _) {
            throw new PublicMenuNotFoundException();
        }
        if (!placement.isEffectivelyActive()) {
            throw new PublicMenuNotFoundException();
        }
        return placement;
    }
}
