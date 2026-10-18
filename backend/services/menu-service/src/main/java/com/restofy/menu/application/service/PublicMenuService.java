package com.restofy.menu.application.service;

import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import com.restofy.menu.domain.entity.ProductSearchTerm;
import com.restofy.menu.domain.exception.CategoryNotFoundException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.PublicMenuNotFoundException;
import com.restofy.menu.infrastructure.persistence.repository.PublicProductSearchRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The read-only customer menu. The restaurant is never taken from the request: the reference in the URL goes
 * through the {@link PublicRestaurantScopeResolver}, and only a restaurant it verified is read (the fail-closed
 * default verifies none). Anything that cannot be shown to this customer, whatever the reason, is the same
 * {@link PublicMenuNotFoundException}. Nothing here writes, orders or grants any authority.
 */
@Service
@Transactional(readOnly = true)
public class PublicMenuService {

    private static final int MAX_REFERENCE_LENGTH = 128;

    private final PublicRestaurantScopeResolver scope;
    private final ActiveCategoryTreeReader categoryTree;
    private final CategoryPlacementResolver placements;
    private final PublicProductSearchRepository search;
    private final ProductViewAssembler views;

    public PublicMenuService(PublicRestaurantScopeResolver scope, ActiveCategoryTreeReader categoryTree,
            CategoryPlacementResolver placements, PublicProductSearchRepository search, ProductViewAssembler views) {
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
                .map(view -> new Card(view, ProductSummary.of(view.product().getDescription())))
                .toList();
        return new PageImpl<>(cards, PageRequest.of(page, size), result.total());
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
