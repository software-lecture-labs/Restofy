package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Management product list: tenant scope, category / subcategory filters, response shape, sorting and
 * paging, against real PostgreSQL. Data is committed per test and removed afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductListApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID STAFF = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final String BASE = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    private UUID drinks;
    private UUID hot;
    private UUID cold;
    private UUID food;
    private UUID pizza;
    private UUID foreignRoot;

    private final List<UUID> creationOrder = new ArrayList<>();

    @BeforeEach
    void data() {
        drinks = saveCategory(Category.root(RESTAURANT_A, "Drinks", 0));
        hot = saveCategory(Category.child(RESTAURANT_A, drinks, "Hot", 0));
        cold = saveCategory(Category.child(RESTAURANT_A, drinks, "Cold", 1));
        food = saveCategory(Category.root(RESTAURANT_A, "Food", 1));
        pizza = saveCategory(Category.child(RESTAURANT_A, food, "Pizza", 0));
        foreignRoot = saveCategory(Category.root(RESTAURANT_B, "Drinks", 0));

        product(RESTAURANT_A, drinks, "Water", "1");
        product(RESTAURANT_A, hot, "Tea", "5");
        product(RESTAURANT_A, cold, "Cola", "8");
        product(RESTAURANT_A, food, "Bread", "3");
        product(RESTAURANT_A, pizza, "Margherita", "20");
        UUID archived = product(RESTAURANT_A, hot, "Old Tea", "4");
        jdbc.update("UPDATE products SET is_active = FALSE WHERE id = ?", archived);
        product(RESTAURANT_B, foreignRoot, "Foreign Water", "1");

        // Distinct, ordered creation times (the default sort is newest first).
        for (int i = 0; i < creationOrder.size(); i++) {
            jdbc.update("UPDATE products SET created_at = timestamptz '2026-01-01 00:00:00+00' + (? * interval '1 minute') WHERE id = ?",
                    i, creationOrder.get(i));
        }
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM categories");
        actor.actAsNobody();
    }

    private UUID saveCategory(Category category) {
        return categories.saveAndFlush(category).getId();
    }

    private UUID product(UUID restaurant, UUID category, String name, String price) {
        UUID id = products.saveAndFlush(Product.create(restaurant, STAFF, category,
                new Product.Details(name, null, new BigDecimal(price), "TRY", BigDecimal.TEN, true))).getId();
        creationOrder.add(id);
        return id;
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get(BASE + (query.isEmpty() ? "" : "?" + query)).with(user("staff")));
    }

    private List<String> names(String query) throws Exception {
        String body = list(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].name");
    }

    // ---- tenant scope ----

    @Test
    void listsOnlyTheCallersRestaurantIncludingArchivedProducts() throws Exception {
        assertEquals(List.of("Old Tea", "Margherita", "Bread", "Cola", "Tea", "Water"), names(""),
                "default order is newest first; archived products are visible to management");
        list("").andExpect(jsonPath("$.totalElements").value(6));
    }

    @Test
    void anotherRestaurantSeesOnlyItsOwnProducts() throws Exception {
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        assertEquals(List.of("Foreign Water"), names(""));
    }

    @Test
    void clientIdentityHeadersDoNotWidenTheTenant() throws Exception {
        String body = mvc.perform(get(BASE).with(user("staff")).header("X-Restaurant-Id", RESTAURANT_B).header("X-Role", "ADMIN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(body, "$.items[*].name");
        assertFalse(names.contains("Foreign Water"));
        assertEquals(6, names.size());
    }

    @Test
    void unauthenticatedAndNonAdminCallersAreRefused() throws Exception {
        actor.actAs(RESTAURANT_A, ActorRole.WAITER);
        list("").andExpect(status().isForbidden());
        actor.actAs(RESTAURANT_A, ActorRole.KITCHEN);
        list("").andExpect(status().isForbidden());
        actor.actAsNobody();
        list("").andExpect(status().isUnauthorized());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    // ---- category filters ----

    @Test
    void mainCategoryFilterCoversItsOwnProductsAndAllSubcategoryProducts() throws Exception {
        assertEquals(List.of("Old Tea", "Cola", "Tea", "Water"), names("categoryId=" + drinks));
        assertEquals(List.of("Margherita", "Bread"), names("categoryId=" + food));
    }

    @Test
    void directOnlyRestrictsAMainCategoryToItsOwnProducts() throws Exception {
        assertEquals(List.of("Water"), names("categoryId=" + drinks + "&directOnly=true"));
    }

    @Test
    void subcategoryFilterCoversOnlyThatSubcategoryWithOrWithoutDirectOnly() throws Exception {
        assertEquals(List.of("Old Tea", "Tea"), names("categoryId=" + hot));
        assertEquals(List.of("Old Tea", "Tea"), names("categoryId=" + hot + "&directOnly=true"));
        assertEquals(List.of("Cola"), names("categoryId=" + cold));
    }

    @Test
    void activeFilterCombinesWithTheCategoryFilter() throws Exception {
        assertEquals(List.of("Old Tea"), names("isActive=false"));
        assertEquals(List.of("Cola", "Tea", "Water"), names("categoryId=" + drinks + "&isActive=true"));
        assertEquals(List.of("Old Tea"), names("categoryId=" + drinks + "&isActive=false"));
    }

    @Test
    void productsOfInactiveCategoriesStayListedForManagement() throws Exception {
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", drinks);
        assertEquals(List.of("Old Tea", "Cola", "Tea", "Water"), names("categoryId=" + drinks));
    }

    @Test
    void anotherRestaurantsOrMissingCategoryIsNotFoundNeverAnEmptyPage() throws Exception {
        list("categoryId=" + foreignRoot).andExpect(status().isNotFound());
        list("categoryId=" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void badFilterInputIsAValidationError() throws Exception {
        list("categoryId=not-a-uuid").andExpect(status().isBadRequest());
        list("directOnly=true").andExpect(status().isBadRequest());
        list("directOnly=maybe").andExpect(status().isBadRequest());
        list("isActive=perhaps").andExpect(status().isBadRequest());
    }

    // ---- response shape ----

    @Test
    void rootProductCarriesItsCategoryAndNoParent() throws Exception {
        list("categoryId=" + drinks + "&directOnly=true")
                .andExpect(jsonPath("$.items[0].categoryId").value(drinks.toString()))
                .andExpect(jsonPath("$.items[0].category.id").value(drinks.toString()))
                .andExpect(jsonPath("$.items[0].category.name").value("Drinks"))
                .andExpect(jsonPath("$.items[0].category.isActive").value(true))
                .andExpect(jsonPath("$.items[0].parentCategory").doesNotExist())
                .andExpect(jsonPath("$.items[0].categoryEffectivelyActive").value(true));
    }

    @Test
    void subcategoryProductCarriesTheDerivedParentCategory() throws Exception {
        list("categoryId=" + cold)
                .andExpect(jsonPath("$.items[0].category.id").value(cold.toString()))
                .andExpect(jsonPath("$.items[0].category.name").value("Cold"))
                .andExpect(jsonPath("$.items[0].parentCategory.id").value(drinks.toString()))
                .andExpect(jsonPath("$.items[0].parentCategory.name").value("Drinks"))
                .andExpect(jsonPath("$.items[0].parentCategory.isActive").value(true));
    }

    @Test
    void effectiveActivenessFollowsTheParentWithoutTouchingTheChildsOwnFlag() throws Exception {
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", drinks);
        list("categoryId=" + cold)
                .andExpect(jsonPath("$.items[0].category.isActive").value(true))
                .andExpect(jsonPath("$.items[0].parentCategory.isActive").value(false))
                .andExpect(jsonPath("$.items[0].categoryEffectivelyActive").value(false));
        list("categoryId=" + food).andExpect(jsonPath("$.items[0].categoryEffectivelyActive").value(true));
    }

    @Test
    void responseReflectsAHierarchyChangeOnTheNextRead() throws Exception {
        jdbc.update("UPDATE categories SET name = 'Beverages' WHERE id = ?", drinks);
        list("categoryId=" + hot).andExpect(jsonPath("$.items[0].parentCategory.name").value("Beverages"));
    }

    @Test
    void detailShowsTheCategoryAndTheParent() throws Exception {
        UUID teaId = jdbc.queryForObject("SELECT id FROM products WHERE name = 'Tea'", UUID.class);
        mvc.perform(get(BASE + "/" + teaId).with(user("staff")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.name").value("Hot"))
                .andExpect(jsonPath("$.parentCategory.name").value("Drinks"))
                .andExpect(jsonPath("$.categoryEffectivelyActive").value(true));
    }

    // ---- sorting and paging ----

    @Test
    void sortsByAllowlistedFieldsInBothDirections() throws Exception {
        assertEquals(List.of("Bread", "Cola", "Margherita", "Old Tea", "Tea", "Water"), names("sort=name"));
        assertEquals(List.of("Water", "Tea", "Old Tea", "Margherita", "Cola", "Bread"), names("sort=name,desc"));
        assertEquals(List.of("Water", "Bread", "Old Tea", "Tea", "Cola", "Margherita"), names("sort=basePrice,asc"));
        assertEquals(List.of("Margherita", "Cola", "Tea", "Old Tea", "Bread", "Water"), names("sort=basePrice,desc"));
        assertEquals(List.of("Water", "Tea", "Cola", "Bread", "Margherita", "Old Tea"), names("sort=createdAt"));
    }

    @Test
    void equalSortKeysAreOrderedByIdSoTheOrderIsRepeatable() throws Exception {
        jdbc.update("UPDATE products SET base_price = 7");
        String first = list("sort=basePrice&size=100").andReturn().getResponse().getContentAsString();
        String second = list("sort=basePrice&size=100").andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        List<String> ids = JsonPath.read(first, "$.items[*].id");
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    void pagesAreDisjointCompleteAndStable() throws Exception {
        List<String> collected = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = list("sort=name&size=2&page=" + page).andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.totalElements").value(6))
                    .andExpect(jsonPath("$.totalPages").value(3)).andReturn().getResponse().getContentAsString();
            collected.addAll(JsonPath.<List<String>>read(body, "$.items[*].name"));
        }
        assertEquals(List.of("Bread", "Cola", "Margherita", "Old Tea", "Tea", "Water"), collected);
        assertEquals(List.of(), names("sort=name&size=2&page=3"));
    }

    @Test
    void invalidPagingAndSortAreRejectedAndNothingLeaks() throws Exception {
        list("page=-1").andExpect(status().isBadRequest());
        list("size=0").andExpect(status().isBadRequest());
        list("size=101").andExpect(status().isBadRequest());
        list("size=abc").andExpect(status().isBadRequest());
        list("sort=password").andExpect(status().isBadRequest());
        list("sort=restaurantId").andExpect(status().isBadRequest());
        list("sort=name,sideways").andExpect(status().isBadRequest());
        list("sort=name,asc,extra").andExpect(status().isBadRequest());
        list("sort=name;drop%20table%20products").andExpect(status().isBadRequest());
        String body = list("sort=password").andReturn().getResponse().getContentAsString();
        assertFalse(body.toLowerCase().contains("exception"));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM products WHERE restaurant_id = ?", Integer.class, RESTAURANT_A));
    }

    @Test
    void largestAllowedPageSizeWorks() throws Exception {
        list("size=100").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    // ---- no per-row queries ----

    @Test
    void categoriesAreLoadedInABatchSoThePageCostDoesNotGrowWithItsSize() throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            // Both measurements use a completely filled page (Spring skips the count query otherwise).
            list("size=6").andExpect(status().isOk());
            statistics.clear();
            list("size=6").andExpect(status().isOk());
            long small = statistics.getPrepareStatementCount();

            for (int i = 0; i < 20; i++) {
                product(RESTAURANT_A, i % 2 == 0 ? hot : pizza, "Extra " + i, "2");
            }
            statistics.clear();
            list("size=26").andExpect(status().isOk());
            long large = statistics.getPrepareStatementCount();

            assertEquals(small, large, "statement count must not depend on how many products are on the page");
            assertTrue(large <= 5, "page + count + categories + parents, got " + large);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
