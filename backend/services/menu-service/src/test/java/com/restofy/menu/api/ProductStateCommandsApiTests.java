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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Publish / hide and open / close commands over HTTP against real PostgreSQL, and the proof that the
 * three state axes (archive, publication, availability) never overwrite each other.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductStateCommandsApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BASE = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID drinks;
    private UUID hot;

    @BeforeEach
    void data() {
        drinks = categories.saveAndFlush(Category.root(RESTAURANT_A, "Drinks", 0)).getId();
        hot = categories.saveAndFlush(Category.child(RESTAURANT_A, drinks, "Hot", 0)).getId();
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

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID createIn(UUID category) throws Exception {
        String json = "{\"categoryId\":\"" + category + "\",\"name\":\"Tea\",\"basePrice\":5,\"currency\":\"TRY\","
                + "\"vatRate\":10,\"priceIncludesVat\":true}";
        String body = send(post(BASE), json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions publication(UUID id, boolean published, long version) throws Exception {
        return send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":" + published + ",\"version\":" + version + "}");
    }

    private ResultActions active(UUID id, boolean active, long version) throws Exception {
        return send(patch(BASE + "/" + id + "/active"), "{\"isActive\":" + active + ",\"version\":" + version + "}");
    }

    private ResultActions availability(UUID id, String status, String code, String text, long version) throws Exception {
        String json = "{\"status\":\"" + status + "\"," + (code == null ? "" : "\"reasonCode\":\"" + code + "\",")
                + (text == null ? "" : "\"reasonText\":\"" + text + "\",") + "\"version\":" + version + "}";
        return send(patch(BASE + "/" + id + "/availability"), json);
    }

    private ResultActions read(UUID id) throws Exception {
        return mvc.perform(get(BASE + "/" + id).with(user("staff")));
    }

    private Map<String, Object> product(UUID id) {
        return jdbc.queryForMap("SELECT is_active, is_published, version, updated_by_user_id FROM products WHERE id = ?", id);
    }

    private Map<String, Object> stored(UUID id) {
        return jdbc.queryForMap("SELECT status, reason_code, reason_text, changed_by_user_id, changed_at, version "
                + "FROM product_availability WHERE product_id = ?", id);
    }

    private int historyCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM product_availability_history WHERE product_id = ?", Integer.class, id);
    }

    // ---- publication ----

    @Test
    void adminPublishesAndHidesWithoutTouchingTheOtherAxes() throws Exception {
        UUID id = createIn(drinks);
        var before = stored(id);
        publication(id, true, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(true))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.isVisibleToCustomer").value(true))
                .andExpect(jsonPath("$.canOrder").value(true));
        publication(id, false, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(false))
                .andExpect(jsonPath("$.isVisibleToCustomer").value(false))
                .andExpect(jsonPath("$.canOrder").value(false))
                .andExpect(jsonPath("$.version").value(2));
        assertEquals(before, stored(id), "publication never touches availability");
        assertEquals(1, historyCount(id), "publication writes no availability history");
    }

    @Test
    void repeatingThePublicationStateIsANoOpWithoutAVersionBump() throws Exception {
        UUID id = createIn(drinks);
        publication(id, false, 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        publication(id, true, 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        publication(id, true, 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void publicationStaleVersionIsAConflict() throws Exception {
        UUID id = createIn(drinks);
        publication(id, true, 0).andExpect(status().isOk());
        publication(id, false, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertEquals(Boolean.TRUE, product(id).get("is_published"));
    }

    @Test
    void publicationRequestNeedsTheFlagAndTheVersion() throws Exception {
        UUID id = createIn(drinks);
        send(patch(BASE + "/" + id + "/publication"), "{\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":\"yes\",\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/publication"), "{}").andExpect(status().isBadRequest());
    }

    @Test
    void anArchivedProductCanBePublishedButStaysInvisibleAndNotOrderable() throws Exception {
        UUID id = createIn(drinks);
        active(id, false, 0).andExpect(status().isOk());
        publication(id, true, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(true))
                .andExpect(jsonPath("$.isActive").value(false))
                .andExpect(jsonPath("$.isVisibleToCustomer").value(false))
                .andExpect(jsonPath("$.canOrder").value(false));
    }

    @Test
    void aProductInAnInactiveCategoryChainCanBePublishedButStaysInvisibleAndNotOrderable() throws Exception {
        UUID id = createIn(hot);
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", drinks);
        publication(id, true, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(true))
                .andExpect(jsonPath("$.category.isActive").value(true))
                .andExpect(jsonPath("$.parentCategory.isActive").value(false))
                .andExpect(jsonPath("$.categoryEffectivelyActive").value(false))
                .andExpect(jsonPath("$.isVisibleToCustomer").value(false))
                .andExpect(jsonPath("$.canOrder").value(false));
        jdbc.update("UPDATE categories SET is_active = TRUE WHERE id = ?", drinks);
        read(id).andExpect(jsonPath("$.isVisibleToCustomer").value(true)).andExpect(jsonPath("$.canOrder").value(true));
    }

    @Test
    void inactiveSubcategoryAlsoHidesAPublishedProduct() throws Exception {
        UUID id = createIn(hot);
        publication(id, true, 0).andExpect(status().isOk());
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", hot);
        read(id).andExpect(jsonPath("$.isVisibleToCustomer").value(false)).andExpect(jsonPath("$.canOrder").value(false));
    }

    // ---- availability ----

    @Test
    void closingKeepsTheReasonTheActorAndTheTimeAndWritesHistory() throws Exception {
        UUID id = createIn(drinks);
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", "no tea left", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.status").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.availability.reasonCode").value("SOLD_OUT"))
                .andExpect(jsonPath("$.availability.reasonText").value("no tea left"))
                .andExpect(jsonPath("$.availability.changedAt").exists())
                .andExpect(jsonPath("$.availability.version").value(1))
                .andExpect(jsonPath("$.version").value(0));
        var row = stored(id);
        assertEquals("OUT_OF_STOCK", row.get("status"));
        assertNotNull(row.get("changed_by_user_id"));
        List<Map<String, Object>> history = jdbc.queryForList("SELECT from_status, to_status, reason_code, reason_text "
                + "FROM product_availability_history WHERE product_id = ? ORDER BY changed_at, id", id);
        assertEquals(2, history.size());
        assertEquals("AVAILABLE", history.get(1).get("from_status"));
        assertEquals("OUT_OF_STOCK", history.get(1).get("to_status"));
        assertEquals("SOLD_OUT", history.get(1).get("reason_code"));
    }

    @Test
    void closingDoesNotChangeProductArchivePublicationOrVersion() throws Exception {
        UUID id = createIn(drinks);
        publication(id, true, 0).andExpect(status().isOk());
        var before = product(id);
        availability(id, "TEMPORARILY_CLOSED", "CLEANING", null, 0).andExpect(status().isOk());
        assertEquals(before, product(id));
    }

    @Test
    void outOfStockAndTemporarilyClosedAreDifferentStates() throws Exception {
        UUID id = createIn(drinks);
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", null, 0).andExpect(jsonPath("$.availability.status").value("OUT_OF_STOCK"));
        availability(id, "TEMPORARILY_CLOSED", "SOLD_OUT", null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.status").value("TEMPORARILY_CLOSED"))
                .andExpect(jsonPath("$.availability.version").value(2));
    }

    @Test
    void closingWithoutAReasonCodeIsRejectedAndChangesNothing() throws Exception {
        UUID id = createIn(drinks);
        availability(id, "OUT_OF_STOCK", null, null, 0).andExpect(status().isBadRequest());
        availability(id, "OUT_OF_STOCK", null, "only text", 0).andExpect(status().isBadRequest());
        availability(id, "TEMPORARILY_CLOSED", "  ", null, 0).andExpect(status().isBadRequest());
        availability(id, "OUT_OF_STOCK", "lower case", null, 0).andExpect(status().isBadRequest());
        availability(id, "OUT_OF_STOCK", "X".repeat(41), null, 0).andExpect(status().isBadRequest());
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", "t".repeat(501), 0).andExpect(status().isBadRequest());
        assertEquals("AVAILABLE", stored(id).get("status"));
        assertEquals(1, historyCount(id));
    }

    @Test
    void invalidOrMissingStatusAndVersionAreValidationErrors() throws Exception {
        UUID id = createIn(drinks);
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"SOLD\",\"reasonCode\":\"X\",\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/availability"), "{\"reasonCode\":\"X\",\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"AVAILABLE\"}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"AVAILABLE\",\"version\":-1}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/availability"), "{}").andExpect(status().isBadRequest());
        assertEquals(0L, stored(id).get("version"));
    }

    @Test
    void reopeningClearsTheCurrentReasonButHistoryKeepsTheClosingReasonAndActor() throws Exception {
        UUID id = createIn(drinks);
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", "gone", 0).andExpect(status().isOk());
        var closedBy = stored(id).get("changed_by_user_id");
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN); // another admin reopens
        availability(id, "AVAILABLE", null, null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.availability.reasonCode").doesNotExist())
                .andExpect(jsonPath("$.availability.version").value(2));

        List<Map<String, Object>> history = jdbc.queryForList("SELECT from_status, to_status, reason_code, reason_text, "
                + "changed_by_user_id FROM product_availability_history WHERE product_id = ? ORDER BY changed_at, id", id);
        assertEquals(3, history.size());
        assertEquals("SOLD_OUT", history.get(1).get("reason_code"), "the closing reason is never lost");
        assertEquals("gone", history.get(1).get("reason_text"));
        assertEquals(closedBy, history.get(1).get("changed_by_user_id"));
        assertEquals("AVAILABLE", history.get(2).get("to_status"));
        assertNotEquals(closedBy, history.get(2).get("changed_by_user_id"));
    }

    @Test
    void reopeningMayCarryAnOptionalNote() throws Exception {
        UUID id = createIn(drinks);
        availability(id, "TEMPORARILY_CLOSED", "CLEANING", null, 0).andExpect(status().isOk());
        availability(id, "AVAILABLE", "RESTOCKED", "delivery arrived", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.reasonCode").value("RESTOCKED"));
    }

    @Test
    void repeatingTheSameChangeIsANoOpThatRecordsNothing() throws Exception {
        UUID id = createIn(drinks);
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", "gone", 0).andExpect(status().isOk());
        var row = stored(id);
        int history = historyCount(id);
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", "gone", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.version").value(1));
        assertEquals(row, stored(id));
        assertEquals(history, historyCount(id));
        availability(id, "AVAILABLE", null, null, 1).andExpect(status().isOk()).andExpect(jsonPath("$.availability.version").value(2));
        availability(id, "AVAILABLE", null, null, 2).andExpect(status().isOk()).andExpect(jsonPath("$.availability.version").value(2));
        assertEquals(history + 1, historyCount(id));
    }

    @Test
    void availabilityStaleVersionIsAConflictAndTheStateStays() throws Exception {
        UUID id = createIn(drinks);
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", null, 0).andExpect(status().isOk());
        availability(id, "TEMPORARILY_CLOSED", "CLEANING", null, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        assertEquals("OUT_OF_STOCK", stored(id).get("status"));
        assertEquals(2, historyCount(id));
    }

    @Test
    void productAndAvailabilityVersionsAreIndependent() throws Exception {
        UUID id = createIn(drinks);
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isOk());
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", null, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.availability.version").value(1));
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed again\",\"version\":1}").andExpect(status().isOk());
        publication(id, true, 2).andExpect(status().isOk());
    }

    @Test
    void aProductWithoutAnAvailabilityRecordIsNotRepairedAndStaysNotOrderable() throws Exception {
        UUID id = createIn(drinks);
        publication(id, true, 0).andExpect(status().isOk());
        jdbc.update("DELETE FROM product_availability_history WHERE product_id = ?", id);
        jdbc.update("DELETE FROM product_availability WHERE product_id = ?", id);

        read(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").doesNotExist())
                .andExpect(jsonPath("$.isVisibleToCustomer").value(true))
                .andExpect(jsonPath("$.canOrder").value(false));
        availability(id, "AVAILABLE", null, null, 0).andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product_availability", Integer.class), "nothing is created silently");
        read(id).andExpect(jsonPath("$.canOrder").value(false));
    }

    @Test
    void identityInTheBodyOrHeadersDoesNotChooseTheActorOrTheTenant() throws Exception {
        UUID id = createIn(drinks);
        UUID forged = UUID.randomUUID();
        mvc.perform(patch(BASE + "/" + id + "/availability").with(user("staff")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_B).header("X-Role", "ADMIN").header("X-User-Id", forged)
                        .content("{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0,"
                                + "\"changedByUserId\":\"" + forged + "\",\"restaurantId\":\"" + RESTAURANT_B + "\"}"))
                .andExpect(status().isOk());
        assertNotEquals(forged, stored(id).get("changed_by_user_id"));
        assertEquals(RESTAURANT_A, jdbc.queryForObject("SELECT restaurant_id FROM product_availability WHERE product_id = ?", UUID.class, id));
    }

    // ---- axes never overwrite each other ----

    @Test
    void archivingHidingAndRestoringNeverReopenAClosedProduct() throws Exception {
        UUID id = createIn(drinks);
        publication(id, true, 0).andExpect(status().isOk());
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", "gone", 0).andExpect(status().isOk());
        var closed = stored(id);
        int history = historyCount(id);

        // The product version advances by one on every real change: 1 -> 7 over these six commands.
        active(id, false, 1).andExpect(status().isOk());
        active(id, true, 2).andExpect(status().isOk());
        publication(id, false, 3).andExpect(status().isOk());
        publication(id, true, 4).andExpect(status().isOk());
        active(id, false, 5).andExpect(status().isOk());
        active(id, true, 6).andExpect(status().isOk());

        assertEquals(closed, stored(id), "the closed state, reason, actor and time are exactly as before");
        assertEquals(history, historyCount(id));
        read(id).andExpect(jsonPath("$.availability.status").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.isVisibleToCustomer").value(true))
                .andExpect(jsonPath("$.canOrder").value(false));
    }

    @Test
    void reopeningNeverPublishesOrRestoresAProduct() throws Exception {
        UUID id = createIn(drinks);
        active(id, false, 0).andExpect(status().isOk());
        availability(id, "TEMPORARILY_CLOSED", "CLEANING", null, 0).andExpect(status().isOk());
        availability(id, "AVAILABLE", null, null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false))
                .andExpect(jsonPath("$.isPublished").value(false))
                .andExpect(jsonPath("$.canOrder").value(false));
    }

    @Test
    void theOrderabilityMatrixHoldsThroughTheApi() throws Exception {
        UUID id = createIn(drinks);
        read(id).andExpect(jsonPath("$.isVisibleToCustomer").value(false)).andExpect(jsonPath("$.canOrder").value(false));   // draft
        publication(id, true, 0).andExpect(jsonPath("$.isVisibleToCustomer").value(true)).andExpect(jsonPath("$.canOrder").value(true));
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", null, 0).andExpect(jsonPath("$.isVisibleToCustomer").value(true)).andExpect(jsonPath("$.canOrder").value(false));
        availability(id, "TEMPORARILY_CLOSED", "CLEANING", null, 1).andExpect(jsonPath("$.isVisibleToCustomer").value(true)).andExpect(jsonPath("$.canOrder").value(false));
        availability(id, "AVAILABLE", null, null, 2).andExpect(jsonPath("$.canOrder").value(true));
        active(id, false, 1).andExpect(jsonPath("$.isVisibleToCustomer").value(false)).andExpect(jsonPath("$.canOrder").value(false));
        active(id, true, 2).andExpect(jsonPath("$.canOrder").value(true));
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", drinks);
        read(id).andExpect(jsonPath("$.isVisibleToCustomer").value(false)).andExpect(jsonPath("$.canOrder").value(false));
    }

    @Test
    void listShowsTheDerivedStatesAndAvailabilityOfEveryRow() throws Exception {
        UUID open = createIn(drinks);
        UUID closed = createIn(hot);
        publication(open, true, 0).andExpect(status().isOk());
        publication(closed, true, 0).andExpect(status().isOk());
        availability(closed, "OUT_OF_STOCK", "SOLD_OUT", null, 0).andExpect(status().isOk());
        String body = mvc.perform(get(BASE + "?sort=createdAt").with(user("staff"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(List.of(true, false), JsonPath.read(body, "$.items[*].canOrder"));
        assertEquals(List.of(true, true), JsonPath.read(body, "$.items[*].isVisibleToCustomer"));
        assertEquals(List.of("AVAILABLE", "OUT_OF_STOCK"), JsonPath.read(body, "$.items[*].availability.status"));
    }

    // ---- security ----

    @Test
    void anotherRestaurantsProductIsNotFoundForBothCommands() throws Exception {
        UUID id = createIn(drinks);
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        publication(id, true, 0).andExpect(status().isNotFound());
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", null, 0).andExpect(status().isNotFound());
        publication(UUID.randomUUID(), true, 0).andExpect(status().isNotFound());
        availability(UUID.randomUUID(), "AVAILABLE", null, null, 0).andExpect(status().isNotFound());
        assertEquals(Boolean.FALSE, product(id).get("is_published"));
        assertEquals("AVAILABLE", stored(id).get("status"));
        assertEquals(1, historyCount(id));
    }

    @Test
    void nonAdminRolesAndMissingActorAreRefusedAndNothingChanges() throws Exception {
        UUID id = createIn(drinks);
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            publication(id, true, 0).andExpect(status().isForbidden());
            availability(id, "OUT_OF_STOCK", "SOLD_OUT", null, 0).andExpect(status().isForbidden());
        }
        actor.actAsNobody();
        publication(id, true, 0).andExpect(status().isUnauthorized());
        availability(id, "OUT_OF_STOCK", "SOLD_OUT", null, 0).andExpect(status().isUnauthorized());
        mvc.perform(patch(BASE + "/" + id + "/availability").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"AVAILABLE\",\"version\":0}")).andExpect(status().isUnauthorized());
        assertEquals(Boolean.FALSE, product(id).get("is_published"));
        assertEquals("AVAILABLE", stored(id).get("status"));
        assertEquals(1, historyCount(id));
    }
}
