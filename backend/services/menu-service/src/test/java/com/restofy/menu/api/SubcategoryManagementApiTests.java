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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Subcategory create/update and the parent relationship, over HTTP against real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class SubcategoryManagementApiTests {

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

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions createSub(UUID parentId, String json) throws Exception {
        return send(post(CATEGORIES + "/" + parentId + "/subcategories"), json);
    }

    private ResultActions updateSub(UUID id, String json) throws Exception {
        return send(patch(SUBCATEGORIES + "/" + id), json);
    }

    private UUID root(UUID restaurant, String name) {
        return repository.saveAndFlush(Category.root(restaurant, name, 0)).getId();
    }

    private UUID child(UUID restaurant, UUID parent, String name) {
        return repository.saveAndFlush(Category.child(restaurant, parent, name, 0)).getId();
    }

    // ---- create ----

    @Test
    void adminCreatesSubcategoryUnderOwnRootCategory() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        createSub(drinks, "{\"name\":\"  Hot   Drinks \",\"sortOrder\":1}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(SUBCATEGORIES + "/")))
                .andExpect(jsonPath("$.name").value("Hot Drinks"))
                .andExpect(jsonPath("$.parentId").value(drinks.toString()))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.version").value(0));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM categories WHERE parent_id = ? AND restaurant_id = ?", Integer.class, drinks, RESTAURANT_A));
    }

    @Test
    void parentOfAnotherRestaurantLooksNonExistent() throws Exception {
        UUID rootOfB = root(RESTAURANT_B, "Drinks");
        createSub(rootOfB, "{\"name\":\"Hot\"}").andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM categories WHERE parent_id IS NOT NULL", Integer.class));
    }

    @Test
    void unknownParentIsNotFound() throws Exception {
        createSub(UUID.randomUUID(), "{\"name\":\"Hot\"}").andExpect(status().isNotFound());
    }

    @Test
    void aSubcategoryCannotBeAParentThirdLevelIsRejected() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        UUID hot = child(RESTAURANT_A, drinks, "Hot");
        createSub(hot, "{\"name\":\"Tea\"}").andExpect(status().isBadRequest());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM categories WHERE parent_id IS NOT NULL", Integer.class));
    }

    @Test
    void parentInBodyIsRejectedBecauseTheParentComesFromTheUrl() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        UUID food = root(RESTAURANT_A, "Food");
        createSub(drinks, "{\"name\":\"Hot\",\"parentId\":\"" + food + "\"}").andExpect(status().isBadRequest());
        send(post(CATEGORIES), "{\"name\":\"Sneaky\",\"parentId\":\"" + food + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void duplicateNameIsConflictUnderTheSameParentButAllowedUnderAnother() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        UUID food = root(RESTAURANT_A, "Food");
        createSub(drinks, "{\"name\":\"Special\"}").andExpect(status().isCreated());
        createSub(drinks, "{\"name\":\" SPECIAL \"}").andExpect(status().isConflict());
        createSub(food, "{\"name\":\"Special\"}").andExpect(status().isCreated());
    }

    @Test
    void subcategoryMayShareANameWithARootCategory() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        createSub(drinks, "{\"name\":\"Drinks\"}").andExpect(status().isCreated());
    }

    @Test
    void invalidSubcategoryBodiesAreRejected() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        createSub(drinks, "{\"name\":\" \"}").andExpect(status().isBadRequest());
        createSub(drinks, "{\"name\":\"" + "x".repeat(151) + "\"}").andExpect(status().isBadRequest());
        createSub(drinks, "{\"name\":\"Ok\",\"sortOrder\":-1}").andExpect(status().isBadRequest());
        send(post(CATEGORIES + "/not-a-uuid/subcategories"), "{\"name\":\"Ok\"}").andExpect(status().isBadRequest());
    }

    @Test
    void createDoesNotMutateTheParent() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        createSub(drinks, "{\"name\":\"Hot\"}").andExpect(status().isCreated());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM categories WHERE id = ?", Long.class, drinks));
        assertTrue(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, drinks));
    }

    // ---- update ----

    @Test
    void adminRenamesReordersAndTogglesActiveOnASubcategory() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        UUID hot = child(RESTAURANT_A, drinks, "Hot");
        updateSub(hot, "{\"name\":\"Warm\",\"sortOrder\":4,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Warm"))
                .andExpect(jsonPath("$.parentId").value(drinks.toString()))
                .andExpect(jsonPath("$.version").value(1));
        updateSub(hot, "{\"isActive\":false,\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
        updateSub(hot, "{\"isActive\":true,\"version\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = ?", Integer.class, hot));
    }

    @Test
    void changingTheParentIsRejectedAndNothingChanges() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        UUID food = root(RESTAURANT_A, "Food");
        UUID hot = child(RESTAURANT_A, drinks, "Hot");
        updateSub(hot, "{\"parentId\":\"" + food + "\",\"version\":0}").andExpect(status().isBadRequest());
        updateSub(hot, "{\"parentId\":\"" + hot + "\",\"name\":\"Loop\",\"version\":0}").andExpect(status().isBadRequest());
        updateSub(hot, "{\"parentId\":\"" + UUID.randomUUID() + "\",\"name\":\"X\",\"version\":0}").andExpect(status().isBadRequest());
        assertEquals(drinks, jdbc.queryForObject("SELECT parent_id FROM categories WHERE id = ?", UUID.class, hot));
        assertEquals("Hot", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, hot));
    }

    @Test
    void aRootCategoryCannotBeMadeASubcategoryThroughUpdate() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        UUID food = root(RESTAURANT_A, "Food");
        send(patch(CATEGORIES + "/" + food), "{\"parentId\":\"" + drinks + "\",\"version\":0}").andExpect(status().isBadRequest());
        assertNull(jdbc.queryForObject("SELECT parent_id FROM categories WHERE id = ?", UUID.class, food));
    }

    @Test
    void staleOrMissingVersionAndEmptyUpdateAreRejected() throws Exception {
        UUID hot = child(RESTAURANT_A, root(RESTAURANT_A, "Drinks"), "Hot");
        updateSub(hot, "{\"name\":\"First\",\"version\":0}").andExpect(status().isOk());
        updateSub(hot, "{\"name\":\"Second\",\"version\":0}").andExpect(status().isConflict());
        updateSub(hot, "{\"name\":\"Third\"}").andExpect(status().isBadRequest());
        updateSub(hot, "{\"version\":1}").andExpect(status().isBadRequest());
        assertEquals("First", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, hot));
    }

    @Test
    void renamingToASiblingNameIsConflict() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        child(RESTAURANT_A, drinks, "Hot");
        UUID cold = child(RESTAURANT_A, drinks, "Cold");
        updateSub(cold, "{\"name\":\"hot\",\"version\":0}").andExpect(status().isConflict());
    }

    @Test
    void deactivatingAParentAndItsChildAreIndependent() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        UUID hot = child(RESTAURANT_A, drinks, "Hot");
        send(patch(CATEGORIES + "/" + drinks), "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        assertTrue(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, hot));
        updateSub(hot, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        assertFalse(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, hot));
        assertFalse(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, drinks));
        // activating a child under an inactive parent does not reactivate the parent either
        updateSub(hot, "{\"isActive\":true,\"version\":1}").andExpect(status().isOk());
        assertFalse(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, drinks));
    }

    // ---- tenant, role, resource separation ----

    @Test
    void anotherRestaurantsSubcategoryLooksNonExistentOnUpdate() throws Exception {
        UUID hotOfA = child(RESTAURANT_A, root(RESTAURANT_A, "Drinks"), "Hot");
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        updateSub(hotOfA, "{\"name\":\"Hacked\",\"isActive\":false,\"version\":0}").andExpect(status().isNotFound());
        assertEquals("Hot", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, hotOfA));
        assertTrue(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, hotOfA));
    }

    @Test
    void rootIdOnSubcategoryResourceAndUnknownIdAreNotFound() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        updateSub(drinks, "{\"name\":\"X\",\"version\":0}").andExpect(status().isNotFound());
        updateSub(UUID.randomUUID(), "{\"name\":\"X\",\"version\":0}").andExpect(status().isNotFound());
    }

    @Test
    void nonAdminAndUnauthenticatedCallersAreRefused() throws Exception {
        UUID drinks = root(RESTAURANT_A, "Drinks");
        UUID hot = child(RESTAURANT_A, drinks, "Hot");
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            createSub(drinks, "{\"name\":\"Cold\"}").andExpect(status().isForbidden());
            updateSub(hot, "{\"name\":\"X\",\"version\":0}").andExpect(status().isForbidden());
        }
        actor.actAsNobody();
        createSub(drinks, "{\"name\":\"Cold\"}").andExpect(status().isUnauthorized());
        updateSub(hot, "{\"name\":\"X\",\"version\":0}").andExpect(status().isUnauthorized());
        mvc.perform(post(CATEGORIES + "/" + drinks + "/subcategories").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Cold\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM categories WHERE parent_id IS NOT NULL", Integer.class));
    }
}
