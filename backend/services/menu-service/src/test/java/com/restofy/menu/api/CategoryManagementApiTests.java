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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Root-category create/update over HTTP against real PostgreSQL. Not @Transactional on purpose:
 * a rejected statement aborts a PostgreSQL transaction, so each test commits and cleans up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class CategoryManagementApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BASE = "/api/v1/menu/categories";

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

    private ResultActions create(String json) throws Exception {
        return send(post(BASE), json);
    }

    private ResultActions update(UUID id, String json) throws Exception {
        return send(patch(BASE + "/" + id), json);
    }

    private UUID createdId(String name) throws Exception {
        String body = create("{\"name\":\"" + name + "\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.<String>read(body, "$.id"));
    }

    // ---- create ----

    @Test
    void adminCreatesRootCategoryForTheirOwnRestaurant() throws Exception {
        create("{\"name\":\"Main Dishes\",\"sortOrder\":2}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(BASE + "/")))
                .andExpect(jsonPath("$.name").value("Main Dishes"))
                .andExpect(jsonPath("$.sortOrder").value(2))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.restaurantId").doesNotExist());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM categories WHERE restaurant_id = ?", Integer.class, RESTAURANT_A));
    }

    @Test
    void restaurantIdInBodyIsIgnoredAndActorRestaurantIsUsed() throws Exception {
        create("{\"name\":\"Sneaky\",\"restaurantId\":\"" + RESTAURANT_B + "\"}")
                .andExpect(status().isCreated());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM categories WHERE restaurant_id = ? AND parent_id IS NULL", Integer.class, RESTAURANT_A));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM categories WHERE restaurant_id = ?", Integer.class, RESTAURANT_B));
    }

    @Test
    void nameIsNormalizedAndSortOrderDefaultsToZero() throws Exception {
        create("{\"name\":\"  Main   Dishes \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Main Dishes"))
                .andExpect(jsonPath("$.sortOrder").value(0));
    }

    @Test
    void invalidCreateBodiesAreRejectedWithoutLeakingInput() throws Exception {
        create("{\"name\":\"   \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        create("{}").andExpect(status().isBadRequest());
        create("{\"name\":\"" + "x".repeat(151) + "\"}").andExpect(status().isBadRequest());
        create("{\"name\":\"Ok\",\"sortOrder\":-1}").andExpect(status().isBadRequest());
        create("{not json").andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class));
    }

    @Test
    void duplicateNameIsConflictCaseInsensitivelyButAllowedInAnotherRestaurant() throws Exception {
        create("{\"name\":\"Drinks\"}").andExpect(status().isCreated());
        create("{\"name\":\"  DRINKS \"}").andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        create("{\"name\":\"Drinks\"}").andExpect(status().isCreated());
    }

    // ---- authentication / role ----

    @Test
    void noVerifiedActorIsUnauthorizedEvenWithAFrameworkLogin() throws Exception {
        actor.actAsNobody();
        create("{\"name\":\"Drinks\"}").andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class));
    }

    @Test
    void requestWithoutAnyAuthenticationIsUnauthorized() throws Exception {
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Drinks\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clientSuppliedIdentityHeadersGrantNothing() throws Exception {
        actor.actAsNobody();
        mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_A).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID())
                        .content("{\"name\":\"Drinks\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminRolesCannotCreateOrUpdate() throws Exception {
        UUID id = createdId("Drinks");
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            create("{\"name\":\"Food\"}").andExpect(status().isForbidden());
            update(id, "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isForbidden());
        }
        assertEquals("Drinks", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, id));
    }

    // ---- update ----

    @Test
    void adminRenamesReordersAndTogglesActiveWithoutDeleting() throws Exception {
        UUID id = createdId("Drinks");
        update(id, "{\"name\":\"Beverages\",\"sortOrder\":5,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Beverages"))
                .andExpect(jsonPath("$.sortOrder").value(5))
                .andExpect(jsonPath("$.version").value(1));
        update(id, "{\"isActive\":false,\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false))
                .andExpect(jsonPath("$.version").value(2));
        update(id, "{\"isActive\":true,\"version\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM categories WHERE id = ?", Integer.class, id));
    }

    @Test
    void deactivatingARootDoesNotChangeItsChildren() throws Exception {
        UUID root = createdId("Drinks");
        Category child = repository.saveAndFlush(Category.child(RESTAURANT_A, root, "Hot", 0));
        update(root, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        assertTrue(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, child.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM categories WHERE id = ?", Long.class, child.getId()));
    }

    @Test
    void staleOrMissingVersionIsRejected() throws Exception {
        UUID id = createdId("Drinks");
        update(id, "{\"name\":\"First\",\"version\":0}").andExpect(status().isOk());
        update(id, "{\"name\":\"Second\",\"version\":0}").andExpect(status().isConflict());
        update(id, "{\"name\":\"Third\"}").andExpect(status().isBadRequest());
        assertEquals("First", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, id));
    }

    @Test
    void emptyUpdateAndInvalidValuesAreBadRequests() throws Exception {
        UUID id = createdId("Drinks");
        update(id, "{\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"name\":\"   \",\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"sortOrder\":-3,\"version\":0}").andExpect(status().isBadRequest());
    }

    @Test
    void renamingToAnExistingNameIsConflict() throws Exception {
        createdId("Drinks");
        UUID food = createdId("Food");
        update(food, "{\"name\":\"drinks\",\"version\":0}").andExpect(status().isConflict());
        assertEquals("Food", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, food));
    }

    // ---- tenant isolation ----

    @Test
    void anotherRestaurantsCategoryLooksNonExistent() throws Exception {
        UUID idOfA = createdId("Drinks");
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        update(idOfA, "{\"name\":\"Hacked\",\"isActive\":false,\"version\":0}").andExpect(status().isNotFound());
        assertEquals("Drinks", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, idOfA));
        assertTrue(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, idOfA));
    }

    @Test
    void unknownIdAndSubcategoryIdAreNotFoundOnTheRootResource() throws Exception {
        update(UUID.randomUUID(), "{\"name\":\"X\",\"version\":0}").andExpect(status().isNotFound());
        UUID root = createdId("Drinks");
        Category child = repository.saveAndFlush(Category.child(RESTAURANT_A, root, "Hot", 0));
        update(child.getId(), "{\"name\":\"X\",\"version\":0}").andExpect(status().isNotFound());
        send(patch(BASE + "/not-a-uuid"), "{\"name\":\"X\",\"version\":0}").andExpect(status().isBadRequest());
    }

    @Test
    void errorResponsesAreProblemDetailsWithoutInternals() throws Exception {
        actor.actAs(RESTAURANT_A, ActorRole.WAITER);
        String body = create("{\"name\":\"Drinks\"}").andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"status\":403"));
        assertFalse(body.toLowerCase().contains("exception"));
        assertFalse(body.contains("com.restofy"));
    }
}
