package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.application.service.PublicProductSort;
import com.restofy.menu.domain.entity.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The public product list, as one tenant-scoped statement. Which products a customer may see is decided here,
 * in SQL, so that the page contents and the total count agree: the product is active and published, its
 * category (and for a subcategory its parent) is active, and it has an availability record (a product
 * without one is hidden). Availability itself does not hide a product, it only closes ordering.
 *
 * <p>All statements are compile-time constants: nothing is ever concatenated into them at run time. The optional
 * filters and the sort are switched by bind parameters (a flag and a value, so no parameter is ever NULL), and the
 * sort key is an enum name. The restaurant, categories, search text, limit and offset are bind parameters too.
 * The search is a plain substring test ({@code strpos}, no LIKE wildcards) on a folded name: Turkish letters map
 * to their ASCII letter, combining marks are dropped and the result is lower-cased with {@code lower()} only after
 * the mapping, so the outcome does not depend on the database collation. The same expression folds the stored name
 * and the search text.
 */
@Repository
public class PublicProductSearchRepository {

    private static final String FOLD_FROM = "\u0131\u0130\u015f\u015e\u011f\u011e\u00fc\u00dc\u00f6\u00d6\u00e7\u00c7"
            + "\u0307\u0327\u0326\u0306\u0308";
    private static final String FOLD_TO = "iisSgGuUoOcC";
    private static final String FOLDED_NAME = "lower(translate(p.name, '" + FOLD_FROM + "', '" + FOLD_TO + "'))";
    private static final String FOLDED_TERM = "lower(translate(:term, '" + FOLD_FROM + "', '" + FOLD_TO + "'))";

    /**
     * Who may be listed at all. This mirrors {@code ProductEligibility.isListedPublicly()} (active, published,
     * category chain active, availability record present) and is shared by the page, the count and the single-product
     * lookup, so none of them can disagree. {@code ProductListingConsistencyApiTests} runs every combination of the
     * state axes through both this SQL and the Java rule.
     */
    private static final String FROM_LISTED = """
             FROM products p
             JOIN categories c ON c.restaurant_id = p.restaurant_id AND c.id = p.category_id
             LEFT JOIN categories parent ON parent.restaurant_id = c.restaurant_id AND parent.id = c.parent_id
             JOIN product_availability a ON a.restaurant_id = p.restaurant_id AND a.product_id = p.id
            WHERE p.restaurant_id = :restaurantId
              AND p.is_active AND p.is_published
              AND c.is_active AND (c.parent_id IS NULL OR parent.is_active)
            """;

    /** A subcategory wins over a main category (which also covers its subcategories); the search is optional. */
    private static final String FROM_WHERE = FROM_LISTED + """
              AND (NOT :hasSubcategory OR c.id = :subcategoryId)
              AND (NOT :hasCategory OR c.id = :categoryId OR c.parent_id = :categoryId)
              AND (NOT :hasTerm OR strpos(""" + FOLDED_NAME + ", " + FOLDED_TERM + """
            ) > 0)
            """;

    private static final String ORDER_BY = " ORDER BY "
            + "CASE WHEN :sortKey = 'NAME' AND :ascending THEN " + FOLDED_NAME + " END ASC, "
            + "CASE WHEN :sortKey = 'NAME' AND NOT :ascending THEN " + FOLDED_NAME + " END DESC, "
            + "CASE WHEN :sortKey = 'NAME' AND :ascending THEN p.name END ASC, "
            + "CASE WHEN :sortKey = 'NAME' AND NOT :ascending THEN p.name END DESC, "
            + "CASE WHEN :sortKey = 'BASE_PRICE' AND :ascending THEN p.base_price END ASC, "
            + "CASE WHEN :sortKey = 'BASE_PRICE' AND NOT :ascending THEN p.base_price END DESC, "
            + "CASE WHEN :sortKey = 'CREATED_AT' AND :ascending THEN p.created_at END ASC, "
            + "CASE WHEN :sortKey = 'CREATED_AT' AND NOT :ascending THEN p.created_at END DESC, "
            + "p.id ASC";

    private static final String SELECT_PAGE = "SELECT p.*" + FROM_WHERE + ORDER_BY + " LIMIT :limit OFFSET :offset";
    private static final String SELECT_COUNT = "SELECT count(*)" + FROM_WHERE;
    private static final String SELECT_ONE = "SELECT p.*" + FROM_LISTED + " AND p.id = :productId";

    /** Stands in for "no id" so that a bind parameter is never NULL; it matches no row because the flag is off. */
    private static final UUID NO_ID = new UUID(0L, 0L);

    @PersistenceContext
    private EntityManager entityManager;

    /** The selected scope: a subcategory wins, otherwise a main category with its subcategories, otherwise all. */
    public record Scope(UUID restaurantId, UUID categoryId, UUID subcategoryId, String term) { }

    public record Result(List<Product> items, long total) { }

    public Result search(Scope scope, PublicProductSort sort, int page, int size) {
        Query items = entityManager.createNativeQuery(SELECT_PAGE, Product.class);
        bindFilters(items, scope);
        items.setParameter("sortKey", sort.field().name());
        items.setParameter("ascending", sort.ascending());
        items.setParameter("limit", size);
        items.setParameter("offset", (long) page * size);

        Query count = entityManager.createNativeQuery(SELECT_COUNT);
        bindFilters(count, scope);

        List<Product> products = items.getResultList().stream().map(Product.class::cast).toList();
        return new Result(products, ((Number) count.getSingleResult()).longValue());
    }

    /**
     * One product, under exactly the conditions of the list. A hidden product, an unknown id and the product of
     * another restaurant are the same empty result, so a direct lookup by id reveals nothing the list would not.
     */
    public Optional<Product> findListed(UUID restaurantId, UUID productId) {
        Query query = entityManager.createNativeQuery(SELECT_ONE, Product.class);
        query.setParameter("restaurantId", restaurantId);
        query.setParameter("productId", productId);
        return query.getResultList().stream().map(Product.class::cast).findFirst();
    }

    private static void bindFilters(Query query, Scope scope) {
        boolean hasSubcategory = scope.subcategoryId() != null;
        boolean hasCategory = !hasSubcategory && scope.categoryId() != null;
        boolean hasTerm = scope.term() != null;
        query.setParameter("restaurantId", scope.restaurantId());
        query.setParameter("hasSubcategory", hasSubcategory);
        query.setParameter("subcategoryId", hasSubcategory ? scope.subcategoryId() : NO_ID);
        query.setParameter("hasCategory", hasCategory);
        query.setParameter("categoryId", hasCategory ? scope.categoryId() : NO_ID);
        query.setParameter("hasTerm", hasTerm);
        query.setParameter("term", hasTerm ? scope.term() : "");
    }
}
