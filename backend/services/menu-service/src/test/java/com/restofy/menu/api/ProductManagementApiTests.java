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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product create / read / update / archive over HTTP against real PostgreSQL. Not @Transactional on
 * purpose: a rejected statement aborts a PostgreSQL transaction, so each test commits and cleans up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductManagementApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BASE = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID categoryA;
    private UUID categoryB;

    @BeforeEach
    void adminOfAWithOneCategoryPerRestaurant() {
        categoryA = categories.saveAndFlush(Category.root(RESTAURANT_A, "Main Dishes", 0)).getId();
        categoryB = categories.saveAndFlush(Category.root(RESTAURANT_B, "Main Dishes", 0)).getId();
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM products");
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

    private ResultActions changeActive(UUID id, String json) throws Exception {
        return send(patch(BASE + "/" + id + "/active"), json);
    }

    private ResultActions read(UUID id) throws Exception {
        return mvc.perform(get(BASE + "/" + id).with(user("staff")));
    }

    private String valid(UUID category, String name) {
        return "{\"categoryId\":\"" + category + "\",\"name\":\"" + name + "\",\"description\":\"Grilled\","
                + "\"basePrice\":125.5,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}";
    }

    private UUID createdId(String name) throws Exception {
        String body = create(valid(categoryA, name)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.<String>read(body, "$.id"));
    }

    private int productCount() {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }

    // ---- create ----

    @Test
    void adminCreatesAnActiveUnpublishedDraftForTheirOwnRestaurant() throws Exception {
        create(valid(categoryA, "Burger"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(BASE + "/")))
                .andExpect(jsonPath("$.name").value("Burger"))
                .andExpect(jsonPath("$.description").value("Grilled"))
                .andExpect(jsonPath("$.categoryId").value(categoryA.toString()))
                .andExpect(jsonPath("$.basePrice").value(125.5))
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.vatRate").value(10))
                .andExpect(jsonPath("$.priceIncludesVat").value(true))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.isPublished").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.restaurantId").doesNotExist());
        assertEquals(RESTAURANT_A, jdbc.queryForObject("SELECT restaurant_id FROM products", UUID.class));
    }

    @Test
    void createStoresTheVerifiedActorAsCreatorAndUpdater() throws Exception {
        createdId("Burger");
        UUID creator = jdbc.queryForObject("SELECT created_by_user_id FROM products", UUID.class);
        UUID updater = jdbc.queryForObject("SELECT updated_by_user_id FROM products", UUID.class);
        assertNotNull(creator);
        assertEquals(creator, updater);
    }

    @Test
    void productCanBelongToASubcategory() throws Exception {
        UUID child = categories.saveAndFlush(Category.child(RESTAURANT_A, categoryA, "Burgers", 0)).getId();
        create(valid(child, "Burger")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(child.toString()));
    }

    @Test
    void nameIsTrimmedAndInnerWhitespaceCollapsedAndBlankDescriptionBecomesNull() throws Exception {
        create("{\"categoryId\":\"" + categoryA + "\",\"name\":\"  Double   Burger \",\"description\":\"   \","
                + "\"basePrice\":1,\"currency\":\"TRY\",\"vatRate\":0,\"priceIncludesVat\":false}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Double Burger"))
                .andExpect(jsonPath("$.description").doesNotExist());
        assertNull(jdbc.queryForObject("SELECT description FROM products", String.class));
    }

    @Test
    void sameNameInTheSameCategoryIsAllowedBecauseNoUniquenessRuleIsApproved() throws Exception {
        createdId("Burger");
        createdId("Burger");
        assertEquals(2, productCount());
    }

    @Test
    void unknownClientFieldsLikeIdAndVersionAreIgnoredNotTrusted() throws Exception {
        UUID forced = UUID.randomUUID();
        String body = valid(categoryA, "Burger").replace("{", "{\"id\":\"" + forced + "\",\"version\":9,");
        create(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(forced.toString())))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void categoryOfAnotherRestaurantLooksLikeAMissingCategory() throws Exception {
        create(valid(categoryB, "Burger")).andExpect(status().isNotFound());
        create(valid(UUID.randomUUID(), "Burger")).andExpect(status().isNotFound());
        assertEquals(0, productCount());
    }

    @Test
    void restaurantStateAndPublicationCannotBeSetInTheBody() throws Exception {
        String foreign = valid(categoryA, "Burger").replace("{", "{\"restaurantId\":\"" + RESTAURANT_B + "\",");
        create(foreign).andExpect(status().isBadRequest());
        create(valid(categoryA, "Burger").replace("{", "{\"isPublished\":true,")).andExpect(status().isBadRequest());
        create(valid(categoryA, "Burger").replace("{", "{\"isActive\":false,")).andExpect(status().isBadRequest());
        assertEquals(0, productCount());
    }

    @Test
    void clientIdentityHeadersDoNotChangeTheTenant() throws Exception {
        mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_B).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID())
                        .content(valid(categoryA, "Burger")))
                .andExpect(status().isCreated());
        assertEquals(RESTAURANT_A, jdbc.queryForObject("SELECT restaurant_id FROM products", UUID.class));
    }

    @Test
    void invalidBodiesAreRejectedWithoutWritingAnything() throws Exception {
        String ok = valid(categoryA, "Burger");
        create("{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        create(ok.replace("\"categoryId\":\"" + categoryA + "\",", "")).andExpect(status().isBadRequest());
        create(ok.replace("\"name\":\"Burger\"", "\"name\":\"   \"")).andExpect(status().isBadRequest());
        create(ok.replace("\"name\":\"Burger\"", "\"name\":\"" + "x".repeat(201) + "\"")).andExpect(status().isBadRequest());
        create(ok.replace("125.5", "-0.01")).andExpect(status().isBadRequest());
        create(ok.replace("125.5", "1.23456")).andExpect(status().isBadRequest());
        create(ok.replace("125.5", "1000000000000000")).andExpect(status().isBadRequest());
        create(ok.replace("\"basePrice\":125.5,", "")).andExpect(status().isBadRequest());
        create(ok.replace("\"TRY\"", "\"try\"")).andExpect(status().isBadRequest());
        create(ok.replace("\"TRY\"", "\"TRYY\"")).andExpect(status().isBadRequest());
        create(ok.replace("\"vatRate\":10", "\"vatRate\":100.5")).andExpect(status().isBadRequest());
        create(ok.replace("\"vatRate\":10", "\"vatRate\":-1")).andExpect(status().isBadRequest());
        create(ok.replace(",\"priceIncludesVat\":true", "")).andExpect(status().isBadRequest());
        create(ok.replace("125.5", "\"abc\"")).andExpect(status().isBadRequest());
        create("{not json").andExpect(status().isBadRequest());
        assertEquals(0, productCount());
    }

    @Test
    void errorBodiesDoNotLeakInternals() throws Exception {
        String body = create(valid(categoryB, "Burger")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(categoryB.toString()));
        assertFalse(body.toLowerCase().contains("exception"));
        assertFalse(body.toLowerCase().contains("sql"));
    }

    // ---- authentication / role ----

    @Test
    void noVerifiedActorIsUnauthorizedEvenWithAFrameworkLogin() throws Exception {
        UUID id = createdId("Burger");
        actor.actAsNobody();
        create(valid(categoryA, "Other")).andExpect(status().isUnauthorized());
        read(id).andExpect(status().isUnauthorized());
        update(id, "{\"name\":\"X\",\"version\":0}").andExpect(status().isUnauthorized());
        changeActive(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isUnauthorized());
        assertEquals(1, productCount());
    }

    @Test
    void requestWithoutAnyAuthenticationIsUnauthorized() throws Exception {
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(valid(categoryA, "Burger")))
                .andExpect(status().isUnauthorized());
        assertEquals(0, productCount());
    }

    @Test
    void nonAdminRolesCannotCreateReadUpdateOrArchive() throws Exception {
        UUID id = createdId("Burger");
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            create(valid(categoryA, "Other")).andExpect(status().isForbidden());
            read(id).andExpect(status().isForbidden());
            update(id, "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isForbidden());
            changeActive(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isForbidden());
        }
        assertEquals("Burger", jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, id));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_active FROM products WHERE id = ?", Boolean.class, id));
    }

    // ---- read ----

    @Test
    void adminReadsTheirOwnProductIncludingAnArchivedOne() throws Exception {
        UUID id = createdId("Burger");
        read(id).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.isActive").value(true));
        changeActive(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        read(id).andExpect(status().isOk()).andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    void anotherRestaurantsProductIsNotFoundAndMissingLooksTheSame() throws Exception {
        UUID id = createdId("Burger");
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        read(id).andExpect(status().isNotFound());
        read(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // ---- update ----

    @Test
    void adminUpdatesFieldsPartiallyAndTheVersionAdvances() throws Exception {
        UUID id = createdId("Burger");
        update(id, "{\"name\":\"Cheeseburger\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cheeseburger"))
                .andExpect(jsonPath("$.basePrice").value(125.5))
                .andExpect(jsonPath("$.version").value(1));
        update(id, "{\"basePrice\":99.99,\"vatRate\":20,\"currency\":\"EUR\",\"priceIncludesVat\":false,"
                + "\"description\":\"Smoked\",\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(99.99))
                .andExpect(jsonPath("$.vatRate").value(20))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.priceIncludesVat").value(false))
                .andExpect(jsonPath("$.description").value("Smoked"))
                .andExpect(jsonPath("$.name").value("Cheeseburger"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void blankDescriptionClearsItAndUpdatingDoesNotPublish() throws Exception {
        UUID id = createdId("Burger");
        update(id, "{\"description\":\"\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.isPublished").value(false));
    }

    @Test
    void adminMovesAProductToAnotherCategoryOfTheirRestaurant() throws Exception {
        UUID id = createdId("Burger");
        UUID drinks = categories.saveAndFlush(Category.root(RESTAURANT_A, "Drinks", 1)).getId();
        update(id, "{\"categoryId\":\"" + drinks + "\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(drinks.toString()));
    }

    @Test
    void movingToAnotherRestaurantsCategoryIsRejectedAndLeavesTheProductUntouched() throws Exception {
        UUID id = createdId("Burger");
        update(id, "{\"categoryId\":\"" + categoryB + "\",\"version\":0}").andExpect(status().isNotFound());
        assertEquals(categoryA, jdbc.queryForObject("SELECT category_id FROM products WHERE id = ?", UUID.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
    }

    @Test
    void staleOrMissingVersionIsRejected() throws Exception {
        UUID id = createdId("Burger");
        update(id, "{\"name\":\"First\",\"version\":0}").andExpect(status().isOk());
        update(id, "{\"name\":\"Second\",\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        update(id, "{\"name\":\"Third\"}").andExpect(status().isBadRequest());
        update(id, "{\"name\":\"Fourth\",\"version\":-1}").andExpect(status().isBadRequest());
        assertEquals("First", jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, id));
    }

    @Test
    void updateWithoutAnyFieldOrWithForbiddenFieldsIsRejected() throws Exception {
        UUID id = createdId("Burger");
        update(id, "{\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"isPublished\":true,\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"restaurantId\":\"" + RESTAURANT_B + "\",\"name\":\"X\",\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"basePrice\":-5,\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"vatRate\":101,\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"currency\":\"tr\",\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"name\":\"" + "y".repeat(201) + "\",\"version\":0}").andExpect(status().isBadRequest());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_active FROM products WHERE id = ?", Boolean.class, id));
    }

    @Test
    void updatingAnotherRestaurantsProductIsNotFoundAndChangesNothing() throws Exception {
        UUID id = createdId("Burger");
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        update(id, "{\"name\":\"Hijacked\",\"version\":0}").andExpect(status().isNotFound());
        changeActive(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isNotFound());
        assertEquals("Burger", jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, id));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_active FROM products WHERE id = ?", Boolean.class, id));
    }

    @Test
    void anUpdateThatChangesNothingKeepsTheVersionAndTheAuditActor() throws Exception {
        UUID id = createdId("Burger");
        UUID creator = jdbc.queryForObject("SELECT created_by_user_id FROM products WHERE id = ?", UUID.class, id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN); // a different admin (new random user id)
        update(id, "{\"name\":\"Burger\",\"basePrice\":125.50,\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        assertEquals(creator, jdbc.queryForObject("SELECT updated_by_user_id FROM products WHERE id = ?", UUID.class, id));
    }

    @Test
    void aRealChangeRecordsTheUpdatingAdminAndKeepsTheCreator() throws Exception {
        UUID id = createdId("Burger");
        UUID creator = jdbc.queryForObject("SELECT created_by_user_id FROM products WHERE id = ?", UUID.class, id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        update(id, "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isOk());
        assertEquals(creator, jdbc.queryForObject("SELECT created_by_user_id FROM products WHERE id = ?", UUID.class, id));
        assertNotEquals(creator, jdbc.queryForObject("SELECT updated_by_user_id FROM products WHERE id = ?", UUID.class, id));
    }

    // ---- archive (soft delete) ----

    @Test
    void archivingKeepsTheRowAndItsIdAndRestoringReactivatesIt() throws Exception {
        UUID id = createdId("Burger");
        changeActive(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.isActive").value(false))
                .andExpect(jsonPath("$.version").value(1));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ? AND NOT is_active", Integer.class, id));
        changeActive(id, "{\"isActive\":true,\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.version").value(2));
        assertEquals(1, productCount());
    }

    @Test
    void archivingDoesNotChangePublicationAndPublicationDoesNotBlockArchiving() throws Exception {
        UUID id = createdId("Burger");
        jdbc.update("UPDATE products SET is_published = TRUE WHERE id = ?", id);
        changeActive(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(true));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_published FROM products WHERE id = ?", Boolean.class, id));
    }

    @Test
    void repeatingTheCurrentStateIsAnIdempotentNoOpWithoutAVersionBump() throws Exception {
        UUID id = createdId("Burger");
        changeActive(id, "{\"isActive\":true,\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        changeActive(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        changeActive(id, "{\"isActive\":false,\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void archivingWithAStaleVersionIsAConflict() throws Exception {
        UUID id = createdId("Burger");
        update(id, "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isOk());
        changeActive(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isConflict());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_active FROM products WHERE id = ?", Boolean.class, id));
    }

    @Test
    void archiveRequestNeedsTheStateAndTheVersion() throws Exception {
        UUID id = createdId("Burger");
        changeActive(id, "{\"version\":0}").andExpect(status().isBadRequest());
        changeActive(id, "{\"isActive\":false}").andExpect(status().isBadRequest());
        changeActive(id, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void thereIsNoHardDeleteEndpoint() throws Exception {
        UUID id = createdId("Burger");
        mvc.perform(delete(BASE + "/" + id).with(user("staff")).with(csrf())).andExpect(status().isMethodNotAllowed());
        assertEquals(1, productCount());
    }

    @Test
    void aCategoryWithProductsCannotBeRemovedFromTheDatabase() throws Exception {
        createdId("Burger");
        var failure = assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM categories WHERE id = ?", categoryA));
        assertTrue(failure.getMessage().contains("fk_products_category"));
    }
}
