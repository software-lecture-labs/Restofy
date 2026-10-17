package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How a product is attached to, and moved between, root categories and subcategories (TASK 2.2.2).
 * Real PostgreSQL, committed per test (a rejected statement would abort an outer transaction).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductCategoryAssignmentApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BASE = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID drinks;        // root of A
    private UUID hot;           // subcategory of drinks
    private UUID cold;          // another subcategory of drinks
    private UUID food;          // another root of A
    private UUID pizza;         // subcategory of food
    private UUID foreignRoot;   // root of B
    private UUID foreignChild;  // subcategory of B's root

    @BeforeEach
    void hierarchy() {
        drinks = save(Category.root(RESTAURANT_A, "Drinks", 0));
        hot = save(Category.child(RESTAURANT_A, drinks, "Hot", 0));
        cold = save(Category.child(RESTAURANT_A, drinks, "Cold", 1));
        food = save(Category.root(RESTAURANT_A, "Food", 1));
        pizza = save(Category.child(RESTAURANT_A, food, "Pizza", 0));
        foreignRoot = save(Category.root(RESTAURANT_B, "Drinks", 0));
        foreignChild = save(Category.child(RESTAURANT_B, foreignRoot, "Hot", 0));
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

    private UUID save(Category category) {
        return categories.saveAndFlush(category).getId();
    }

    private void deactivate(UUID id) {
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", id);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String body(UUID category) {
        return "{\"categoryId\":\"" + category + "\",\"name\":\"Item\",\"basePrice\":0,\"currency\":\"TRY\","
                + "\"vatRate\":10,\"priceIncludesVat\":true}";
    }

    private UUID createIn(UUID category) throws Exception {
        String json = send(post(BASE), body(category)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.<String>read(json, "$.id"));
    }

    private ResultActions move(UUID product, UUID category, long version) throws Exception {
        return send(patch(BASE + "/" + product), "{\"categoryId\":\"" + category + "\",\"version\":" + version + "}");
    }

    private UUID categoryOf(UUID product) {
        return jdbc.queryForObject("SELECT category_id FROM products WHERE id = ?", UUID.class, product);
    }

    private long versionOf(UUID product) {
        return jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, product);
    }

    // ---- model ----

    @Test
    void theProductTableHoldsNoSecondCategoryOrParentReference() {
        var categoryColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'products' AND (column_name LIKE '%categor%' OR column_name LIKE '%parent%')""",
                String.class);
        assertEquals(java.util.List.of("category_id"), categoryColumns);
    }

    // ---- attaching on create ----

    @Test
    void productIsAttachedToARootCategory() throws Exception {
        UUID id = createIn(drinks);
        assertEquals(drinks, categoryOf(id));
    }

    @Test
    void productIsAttachedToASubcategory() throws Exception {
        UUID id = createIn(hot);
        assertEquals(hot, categoryOf(id));
    }

    @Test
    void draftProductCanBeAttachedToAnInactiveRootAndStaysAnUnpublishedActiveRecord() throws Exception {
        deactivate(drinks);
        send(post(BASE), body(drinks)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(drinks.toString()))
                .andExpect(jsonPath("$.isPublished").value(false))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void draftProductCanBeAttachedToAnInactiveSubcategory() throws Exception {
        deactivate(hot);
        send(post(BASE), body(hot)).andExpect(status().isCreated()).andExpect(jsonPath("$.isPublished").value(false));
    }

    @Test
    void draftProductCanBeAttachedToAnActiveSubcategoryUnderAnInactiveRoot() throws Exception {
        deactivate(drinks);
        send(post(BASE), body(hot)).andExpect(status().isCreated());
        assertTrue(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, hot),
                "deactivating the root never rewrites the child's stored flag");
    }

    @Test
    void categoryOfAnotherRestaurantAndAMissingCategoryGetTheSameAnswer() throws Exception {
        String foreign = send(post(BASE), body(foreignRoot)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String foreignSub = send(post(BASE), body(foreignChild)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = send(post(BASE), body(UUID.randomUUID())).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertEquals(JsonPath.<String>read(missing, "$.detail"), JsonPath.<String>read(foreign, "$.detail"));
        assertEquals(JsonPath.<String>read(missing, "$.detail"), JsonPath.<String>read(foreignSub, "$.detail"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class));
    }

    @Test
    void malformedOrMissingCategoryIdIsAValidationError() throws Exception {
        send(post(BASE), body(drinks).replace(drinks.toString(), "not-a-uuid")).andExpect(status().isBadRequest());
        send(post(BASE), body(drinks).replace("\"categoryId\":\"" + drinks + "\",", "")).andExpect(status().isBadRequest());
        send(post(BASE), body(drinks).replace("\"" + drinks + "\"", "null")).andExpect(status().isBadRequest());
    }

    @Test
    void clientIdentityHeadersCannotReachAnotherRestaurantsCategory() throws Exception {
        mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_B).header("X-Role", "ADMIN")
                        .content(body(foreignRoot)))
                .andExpect(status().isNotFound());
    }

    // ---- moving between categories ----

    @Test
    void productMovesFromRootToSubcategoryBackToRootAndBetweenSubcategories() throws Exception {
        UUID id = createIn(drinks);
        move(id, hot, 0).andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").value(hot.toString()))
                .andExpect(jsonPath("$.version").value(1));
        move(id, cold, 1).andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").value(cold.toString()))
                .andExpect(jsonPath("$.version").value(2));
        move(id, pizza, 2).andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").value(pizza.toString()));
        move(id, food, 3).andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").value(food.toString()))
                .andExpect(jsonPath("$.version").value(4));
        assertEquals(food, categoryOf(id));
    }

    @Test
    void movingToTheCurrentCategoryIsANoOpThatKeepsTheVersion() throws Exception {
        UUID id = createIn(hot);
        move(id, hot, 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        assertEquals(0L, versionOf(id));
    }

    @Test
    void productCanBeMovedIntoAndOutOfInactiveCategoriesWithoutBecomingPublished() throws Exception {
        UUID id = createIn(drinks);
        deactivate(cold);
        move(id, cold, 0).andExpect(status().isOk()).andExpect(jsonPath("$.isPublished").value(false));
        move(id, food, 1).andExpect(status().isOk());
    }

    @Test
    void movingToAnotherRestaurantsOrMissingCategoryIsRejectedAndChangesNothing() throws Exception {
        UUID id = createIn(hot);
        for (UUID target : new UUID[] {foreignRoot, foreignChild, UUID.randomUUID()}) {
            move(id, target, 0).andExpect(status().isNotFound());
        }
        assertEquals(hot, categoryOf(id));
        assertEquals(0L, versionOf(id));
    }

    @Test
    void movingWithAStaleVersionIsAConflictAndLeavesTheCategory() throws Exception {
        UUID id = createIn(drinks);
        move(id, hot, 0).andExpect(status().isOk());
        move(id, cold, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertEquals(hot, categoryOf(id));
        assertEquals(1L, versionOf(id));
    }

    @Test
    void anotherRestaurantsAdminCannotMoveMyProductEvenIntoTheirOwnCategory() throws Exception {
        UUID id = createIn(hot);
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        move(id, foreignRoot, 0).andExpect(status().isNotFound());
        assertEquals(hot, categoryOf(id));
    }

    @Test
    void nonAdminCannotMoveAProduct() throws Exception {
        UUID id = createIn(hot);
        actor.actAs(RESTAURANT_A, ActorRole.WAITER);
        move(id, cold, 0).andExpect(status().isForbidden());
        assertEquals(hot, categoryOf(id));
    }

    @Test
    void aMoveRecordsTheActingAdminButOnlyWhenTheCategoryReallyChanges() throws Exception {
        UUID id = createIn(drinks);
        UUID creator = jdbc.queryForObject("SELECT created_by_user_id FROM products WHERE id = ?", UUID.class, id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        move(id, drinks, 0).andExpect(status().isOk());
        assertEquals(creator, jdbc.queryForObject("SELECT updated_by_user_id FROM products WHERE id = ?", UUID.class, id));
        move(id, hot, 0).andExpect(status().isOk());
        assertNotEquals(creator, jdbc.queryForObject("SELECT updated_by_user_id FROM products WHERE id = ?", UUID.class, id));
    }
}
