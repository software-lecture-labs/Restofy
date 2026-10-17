package com.restofy.menu.api;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Management list/detail contract against real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class CategoryQueryApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String CATEGORIES = "/api/v1/menu/categories";
    private static final String SUBCATEGORIES = "/api/v1/menu/subcategories";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void adminOfA() {
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM categories");
        actor.actAsNobody();
    }

    private ResultActions fetch(String url) throws Exception {
        return mvc.perform(get(url).with(user("staff")));
    }

    private UUID root(UUID restaurant, String name, int sortOrder, boolean active) {
        Category category = Category.root(restaurant, name, sortOrder);
        if (!active) category.deactivate();
        return repository.saveAndFlush(category).getId();
    }

    private UUID child(UUID restaurant, UUID parent, String name, int sortOrder, boolean active) {
        Category category = Category.child(restaurant, parent, name, sortOrder);
        if (!active) category.deactivate();
        return repository.saveAndFlush(category).getId();
    }

    // ---- root list ----

    @Test
    void emptyRestaurantGetsAnEmptyPageNotAnError() throws Exception {
        fetch(CATEGORIES).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void listShowsOnlyOwnRootCategoriesIncludingInactiveOnesInStableOrder() throws Exception {
        root(RESTAURANT_A, "Desserts", 2, true);
        root(RESTAURANT_A, "Drinks", 1, false);
        root(RESTAURANT_A, "Food", 1, true);
        root(RESTAURANT_B, "Other tenant", 0, true);
        UUID parent = root(RESTAURANT_A, "Parent", 9, true);
        child(RESTAURANT_A, parent, "Child must not appear in the root list", 0, true);

        String first = fetch(CATEGORIES).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.items[0].sortOrder").value(1))
                .andExpect(jsonPath("$.items[2].name").value("Desserts"))
                .andExpect(jsonPath("$.items[3].name").value("Parent"))
                .andExpect(jsonPath("$.items[*].restaurantId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, fetch(CATEGORIES).andReturn().getResponse().getContentAsString(), "order must be deterministic");
        assertFalse(first.contains("Other tenant"));
        assertFalse(first.contains("Child must not appear"));
    }

    @Test
    void isActiveFilterAndNameSorting() throws Exception {
        root(RESTAURANT_A, "Beta", 0, true);
        root(RESTAURANT_A, "Alpha", 0, false);
        root(RESTAURANT_A, "Gamma", 0, true);
        fetch(CATEGORIES + "?isActive=true&sort=name,desc").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Gamma"))
                .andExpect(jsonPath("$.items[1].name").value("Beta"));
        fetch(CATEGORIES + "?isActive=false").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Alpha"))
                .andExpect(jsonPath("$.items[0].isActive").value(false));
    }

    @Test
    void paginationCoversEveryRowExactlyOnceEvenWithEqualSortOrder() throws Exception {
        for (int i = 0; i < 5; i++) root(RESTAURANT_A, "Cat " + i, 0, true);
        var seen = new java.util.HashSet<String>();
        int[] expectedSizes = {2, 2, 1};
        for (int page = 0; page < 3; page++) {
            String body = fetch(CATEGORIES + "?page=" + page + "&size=2").andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.page").value(page))
                    .andExpect(jsonPath("$.items.length()").value(expectedSizes[page]))
                    .andReturn().getResponse().getContentAsString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(body);
            while (m.find()) assertTrue(seen.add(m.group(1)), "row repeated across pages");
        }
        assertEquals(5, seen.size());
        fetch(CATEGORIES + "?page=3&size=2").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void invalidPagingAndSortParametersAreBadRequests() throws Exception {
        for (String query : new String[] {
                "page=-1", "size=0", "size=101", "size=abc", "page=x", "isActive=maybe",
                "sort=restaurantId", "sort=name,sideways", "sort=name,asc,extra", "sort=id",
                "sort=name;DROP TABLE categories", "sort=parent.name", "sort=,asc"}) {
            fetch(CATEGORIES + "?" + query).andExpect(status().isBadRequest());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'categories'", Integer.class));
    }

    // ---- root detail ----

    @Test
    void rootDetailIsReturnedForTheOwnerIncludingWhenInactive() throws Exception {
        UUID id = root(RESTAURANT_A, "Drinks", 3, false);
        fetch(CATEGORIES + "/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Drinks"))
                .andExpect(jsonPath("$.isActive").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.restaurantId").doesNotExist());
    }

    @Test
    void rootDetailOfAnotherRestaurantUnknownIdOrSubcategoryIsNotFound() throws Exception {
        UUID ofB = root(RESTAURANT_B, "Secret", 0, true);
        UUID parent = root(RESTAURANT_A, "Drinks", 0, true);
        UUID sub = child(RESTAURANT_A, parent, "Hot", 0, true);
        fetch(CATEGORIES + "/" + ofB).andExpect(status().isNotFound());
        fetch(CATEGORIES + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
        fetch(CATEGORIES + "/" + sub).andExpect(status().isNotFound());
        fetch(CATEGORIES + "/not-a-uuid").andExpect(status().isBadRequest());
    }

    // ---- subcategory list / detail ----

    @Test
    void subcategoryListIsScopedToTheParentAndFilterable() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks", 0, true);
        UUID food = root(RESTAURANT_A, "Food", 0, true);
        child(RESTAURANT_A, drinks, "Hot", 1, true);
        child(RESTAURANT_A, drinks, "Cold", 0, false);
        child(RESTAURANT_A, food, "Pizza", 0, true);

        fetch(CATEGORIES + "/" + drinks + "/subcategories").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Cold"))
                .andExpect(jsonPath("$.items[1].name").value("Hot"))
                .andExpect(jsonPath("$.items[*].parentId").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(drinks.toString()))));
        fetch(CATEGORIES + "/" + drinks + "/subcategories?isActive=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Hot"));
        fetch(CATEGORIES + "/" + drinks + "/subcategories?page=1&size=1&sort=name,desc").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Cold"))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void subcategoryListOfAnInactiveParentIsStillVisibleToTheAdmin() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks", 0, false);
        child(RESTAURANT_A, drinks, "Hot", 0, true);
        fetch(CATEGORIES + "/" + drinks + "/subcategories").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void subcategoryListOfForeignUnknownOrNonRootParentIsNotFound() throws Exception {
        UUID ofB = root(RESTAURANT_B, "Drinks", 0, true);
        child(RESTAURANT_B, ofB, "Hot", 0, true);
        UUID drinks = root(RESTAURANT_A, "Drinks", 0, true);
        UUID hot = child(RESTAURANT_A, drinks, "Hot", 0, true);
        fetch(CATEGORIES + "/" + ofB + "/subcategories").andExpect(status().isNotFound());
        fetch(CATEGORIES + "/" + UUID.randomUUID() + "/subcategories").andExpect(status().isNotFound());
        fetch(CATEGORIES + "/" + hot + "/subcategories").andExpect(status().isNotFound());
        fetch(CATEGORIES + "/" + drinks + "/subcategories?size=500").andExpect(status().isBadRequest());
    }

    @Test
    void subcategoryDetailRespectsTenantAndLevel() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks", 0, true);
        UUID hot = child(RESTAURANT_A, drinks, "Hot", 0, true);
        UUID ofB = child(RESTAURANT_B, root(RESTAURANT_B, "Drinks", 0, true), "Hot", 0, true);
        fetch(SUBCATEGORIES + "/" + hot).andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(drinks.toString()))
                .andExpect(jsonPath("$.name").value("Hot"));
        fetch(SUBCATEGORIES + "/" + ofB).andExpect(status().isNotFound());
        fetch(SUBCATEGORIES + "/" + drinks).andExpect(status().isNotFound());
        fetch(SUBCATEGORIES + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // ---- role / authentication ----

    @Test
    void readsRequireAnAdminOfARestaurant() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks", 0, true);
        UUID hot = child(RESTAURANT_A, drinks, "Hot", 0, true);
        String[] urls = {CATEGORIES, CATEGORIES + "/" + drinks, CATEGORIES + "/" + drinks + "/subcategories", SUBCATEGORIES + "/" + hot};
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            for (String url : urls) fetch(url).andExpect(status().isForbidden());
        }
        actor.actAsNobody();
        for (String url : urls) fetch(url).andExpect(status().isUnauthorized());
        for (String url : urls) mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    @Test
    void adminOfAnotherRestaurantSeesNothingOfThisOne() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks", 0, true);
        child(RESTAURANT_A, drinks, "Hot", 0, true);
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        fetch(CATEGORIES).andExpect(jsonPath("$.totalElements").value(0));
        fetch(CATEGORIES + "/" + drinks).andExpect(status().isNotFound());
        fetch(CATEGORIES + "/" + drinks + "/subcategories").andExpect(status().isNotFound());
    }
}
