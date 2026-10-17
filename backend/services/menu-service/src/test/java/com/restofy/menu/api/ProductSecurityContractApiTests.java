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
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error contract, mass assignment, tenant scope, optimistic locking and audit behaviour for the price
 * and VAT fields, against real PostgreSQL. A price edit must never move another state axis.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductSecurityContractApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BASE = "/api/v1/menu/products";
    private static final List<String> LEAKS = List.of("exception", "org.springframework", "java.", "jakarta", "hibernate",
            "postgres", "select ", "insert ", "sql", "stacktrace", "trace", "constraint", "at com.restofy", "ck_products", "fk_products");

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID categoryA;
    private UUID categoryB;

    @BeforeEach
    void data() {
        categoryA = categories.saveAndFlush(Category.root(RESTAURANT_A, "Main", 0)).getId();
        categoryB = categories.saveAndFlush(Category.root(RESTAURANT_B, "Main", 0)).getId();
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
        actor.actAsNobody();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String body() {
        return "{\"categoryId\":\"" + categoryA + "\",\"name\":\"Tea\",\"basePrice\":5,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}";
    }

    private UUID seeded() throws Exception {
        String json = send(post(BASE), body()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM products WHERE id = ?", id);
    }

    private Map<String, Object> availabilityRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM product_availability WHERE product_id = ?", id);
    }

    private int historyCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM product_availability_history WHERE product_id = ?", Integer.class, id);
    }

    private void assertProblem(MvcResult result, int status) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertEquals(status, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"), result.getResponse().getContentType());
        assertEquals(status, ((Number) JsonPath.read(content, "$.status")).intValue());
        assertNotNull(JsonPath.read(content, "$.title"));
        assertNotNull(JsonPath.read(content, "$.detail"));
        String lower = content.toLowerCase();
        for (String leak : LEAKS) {
            assertFalse(lower.contains(leak), "error body must not contain '" + leak + "': " + content);
        }
    }

    // ---- one error contract ----

    @Test
    void everyFailureIsAProblemJsonWithoutInternalDetail() throws Exception {
        UUID id = seeded();
        String path = BASE + "/" + id;
        assertProblem(send(post(BASE), "{}").andReturn(), 400);                                   // bean validation
        assertProblem(send(post(BASE), body().replace("\"TRY\"", "\"ZZZ\"")).andReturn(), 400);  // domain validation
        assertProblem(send(post(BASE), "{not json").andReturn(), 400);                            // unreadable body
        assertProblem(send(post(BASE), body().replace("5", "\"abc\"")).andReturn(), 400);         // wrong type
        assertProblem(send(patch(path), "{\"name\":\"x\",\"version\":99}").andReturn(), 409);      // stale version
        assertProblem(send(patch(BASE + "/" + UUID.randomUUID()), "{\"name\":\"x\",\"version\":0}").andReturn(), 404);
        assertProblem(send(post(BASE), body().replace(categoryA.toString(), UUID.randomUUID().toString())).andReturn(), 404);
        actor.actAs(RESTAURANT_A, ActorRole.WAITER);
        assertProblem(send(patch(path), "{\"name\":\"x\",\"version\":0}").andReturn(), 403);
        actor.actAsNobody();
        assertProblem(send(patch(path), "{\"name\":\"x\",\"version\":0}").andReturn(), 401);
    }

    @Test
    void validationErrorsNameTheFieldButNeverEchoTheSubmittedValue() throws Exception {
        String secret = "SUPER-SECRET-VALUE-12345";
        MvcResult result = send(post(BASE), body().replace("\"TRY\"", "\"" + secret + "\"")).andReturn();
        String content = result.getResponse().getContentAsString();
        assertEquals(400, result.getResponse().getStatus());
        assertFalse(content.contains(secret), content);
        assertEquals("currency", JsonPath.read(content, "$.errors[0].field"));
        MvcResult domain = send(post(BASE), body().replace("\"name\":\"Tea\"", "\"name\":\"" + secret + "\\u0000\"")).andReturn();
        assertEquals(400, domain.getResponse().getStatus());
        assertFalse(domain.getResponse().getContentAsString().contains(secret));
    }

    @Test
    void aMalformedIdentifierInThePathDoesNotEchoTheValueBack() throws Exception {
        String marker = "not-a-uuid-MARKER";
        MvcResult result = mvc.perform(get(BASE + "/" + marker).with(user("staff"))).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertFalse(((String) JsonPath.read(result.getResponse().getContentAsString(), "$.detail")).contains(marker));
        MvcResult list = mvc.perform(get(BASE + "?categoryId=" + marker).with(user("staff"))).andReturn();
        assertEquals(400, list.getResponse().getStatus());
        assertFalse(((String) JsonPath.read(list.getResponse().getContentAsString(), "$.detail")).contains(marker));
    }

    @Test
    void anotherTenantsIdentifiersNeverAppearInAnyErrorBody() throws Exception {
        UUID id = seeded();
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        for (MvcResult result : List.of(
                send(patch(BASE + "/" + id), "{\"basePrice\":1,\"version\":0}").andReturn(),
                mvc.perform(get(BASE + "/" + id).with(user("staff"))).andReturn(),
                send(post(BASE), body()).andReturn())) {
            String content = result.getResponse().getContentAsString();
            assertEquals(404, result.getResponse().getStatus());
            // The product id the caller put in its own URL appears in "instance"; nothing else of tenant A may.
            assertFalse(((String) JsonPath.read(content, "$.detail")).contains(id.toString()));
            assertFalse(content.contains(categoryA.toString()));
            assertFalse(content.contains(RESTAURANT_A.toString()));
        }
    }

    // ---- mass assignment ----

    @Test
    void serverControlledFieldsInTheCreateBodyAreNeverAssigned() throws Exception {
        UUID forgedId = UUID.randomUUID();
        UUID forgedUser = UUID.randomUUID();
        String forged = body().replace("{", "{\"id\":\"" + forgedId + "\",\"version\":7,\"createdAt\":\"2001-01-01T00:00:00Z\","
                + "\"updatedAt\":\"2001-01-01T00:00:00Z\",\"createdByUserId\":\"" + forgedUser + "\",\"updatedByUserId\":\"" + forgedUser + "\","
                + "\"canOrder\":true,\"isVisibleToCustomer\":true,\"categoryEffectivelyActive\":true,"
                + "\"availability\":{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"FORGED\",\"version\":9},"
                + "\"category\":{\"id\":\"" + categoryB + "\"},\"parentCategory\":{\"id\":\"" + categoryB + "\"},");
        String json = send(post(BASE), forged).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        assertNotEquals(forgedId, id);
        var product = row(id);
        assertEquals(RESTAURANT_A, product.get("restaurant_id"));
        assertEquals(categoryA, product.get("category_id"));
        assertEquals(0L, product.get("version"));
        assertNotEquals(forgedUser, product.get("created_by_user_id"));
        assertNotEquals(forgedUser, product.get("updated_by_user_id"));
        assertTrue(((java.sql.Timestamp) product.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC).getYear() > 2001);
        assertEquals(Boolean.FALSE, product.get("is_published"));
        var availability = availabilityRow(id);
        assertEquals("AVAILABLE", availability.get("status"));
        assertNull(availability.get("reason_code"));
        assertEquals(0L, availability.get("version"));
    }

    @Test
    void serverControlledFieldsInAnUpdateBodyAreNeverAssigned() throws Exception {
        UUID id = seeded();
        var before = row(id);
        UUID forgedUser = UUID.randomUUID();
        String forged = "{\"basePrice\":6,\"version\":0,\"id\":\"" + UUID.randomUUID() + "\",\"createdAt\":\"2001-01-01T00:00:00Z\","
                + "\"updatedAt\":\"2001-01-01T00:00:00Z\",\"createdByUserId\":\"" + forgedUser + "\",\"updatedByUserId\":\"" + forgedUser + "\","
                + "\"canOrder\":true,\"isVisibleToCustomer\":true,\"availability\":{\"status\":\"OUT_OF_STOCK\"}}";
        send(patch(BASE + "/" + id), forged).andExpect(status().isOk());
        var after = row(id);
        assertEquals(before.get("id"), after.get("id"));
        assertEquals(before.get("created_at"), after.get("created_at"));
        assertEquals(before.get("created_by_user_id"), after.get("created_by_user_id"));
        assertNotEquals(forgedUser, after.get("updated_by_user_id"));
        assertEquals("AVAILABLE", availabilityRow(id).get("status"));
    }

    @Test
    void restaurantAndStateFlagsInTheBodyAreRejectedNotSilentlyApplied() throws Exception {
        UUID id = seeded();
        for (String field : new String[] {"\"restaurantId\":\"" + RESTAURANT_B + "\"", "\"isPublished\":true", "\"isActive\":false"}) {
            send(post(BASE), body().replace("{", "{" + field + ",")).andExpect(status().isBadRequest());
            send(patch(BASE + "/" + id), "{\"basePrice\":9,\"version\":0," + field + "}").andExpect(status().isBadRequest());
        }
        assertEquals(0L, row(id).get("version"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class));
    }

    @Test
    void theCommandEndpointsIgnoreExtraFieldsToo() throws Exception {
        UUID id = seeded();
        UUID forged = UUID.randomUUID();
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0,"
                + "\"changedAt\":\"2001-01-01T00:00:00Z\",\"changedByUserId\":\"" + forged + "\",\"basePrice\":1,\"isPublished\":true}")
                .andExpect(status().isOk());
        var product = row(id);
        assertEquals(Boolean.FALSE, product.get("is_published"));
        assertEquals(0, new java.math.BigDecimal("5").compareTo((java.math.BigDecimal) product.get("base_price")));
        assertNotEquals(forged, availabilityRow(id).get("changed_by_user_id"));
        assertTrue(((java.sql.Timestamp) availabilityRow(id).get("changed_at")).toInstant().atOffset(java.time.ZoneOffset.UTC).getYear() > 2001);
    }

    // ---- tenant isolation and ADMIN scope for price edits ----

    @Test
    void anotherRestaurantCannotReadOrChangeAProductsPriceVatOrCurrency() throws Exception {
        UUID id = seeded();
        var before = row(id);
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        send(patch(BASE + "/" + id), "{\"basePrice\":0,\"vatRate\":0,\"currency\":\"USD\",\"priceIncludesVat\":false,\"version\":0}")
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + id).with(user("staff"))).andExpect(status().isNotFound());
        String list = mvc.perform(get(BASE).with(user("staff"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0, ((Number) JsonPath.read(list, "$.totalElements")).intValue());
        assertEquals(before, row(id));
    }

    @Test
    void aProductCannotBeCreatedOrMovedIntoAnotherRestaurantsCategoryEvenWithAValidPrice() throws Exception {
        UUID id = seeded();
        send(post(BASE), body().replace(categoryA.toString(), categoryB.toString())).andExpect(status().isNotFound());
        send(patch(BASE + "/" + id), "{\"categoryId\":\"" + categoryB + "\",\"basePrice\":1,\"version\":0}").andExpect(status().isNotFound());
        assertEquals(categoryA, row(id).get("category_id"));
        assertEquals(0, new java.math.BigDecimal("5").compareTo((java.math.BigDecimal) row(id).get("base_price")),
                "the price in the same rejected request was not applied either");
    }

    @Test
    void onlyAnAdminMayChangePricesAndNoOneElseLearnsAnything() throws Exception {
        UUID id = seeded();
        var before = row(id);
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            send(patch(BASE + "/" + id), "{\"basePrice\":1,\"version\":0}").andExpect(status().isForbidden());
            send(post(BASE), body()).andExpect(status().isForbidden());
        }
        actor.actAsNobody();
        send(patch(BASE + "/" + id), "{\"basePrice\":1,\"version\":0}").andExpect(status().isUnauthorized());
        send(post(BASE), body()).andExpect(status().isUnauthorized());
        assertEquals(before, row(id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class));
    }

    @Test
    void identityHeadersAndBodyCannotChooseTheTenantForAPriceEdit() throws Exception {
        UUID id = seeded();
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        mvc.perform(patch(BASE + "/" + id).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_A).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID())
                        .content("{\"basePrice\":1,\"version\":0}"))
                .andExpect(status().isNotFound());
        assertEquals(0, new java.math.BigDecimal("5").compareTo((java.math.BigDecimal) row(id).get("base_price")));
    }

    // ---- a price edit never moves another axis ----

    @Test
    void editingPriceVatCurrencyAndInclusionNeverChangesArchivePublicationOrAvailability() throws Exception {
        UUID id = seeded();
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true,\"version\":0}").andExpect(status().isOk());
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"reasonText\":\"gone\",\"version\":0}")
                .andExpect(status().isOk());
        var availability = availabilityRow(id);
        int history = historyCount(id);

        send(patch(BASE + "/" + id), "{\"basePrice\":0,\"vatRate\":18,\"currency\":\"EUR\",\"priceIncludesVat\":false,\"version\":1}")
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.isPublished").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.isActive").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.availability.status").value("OUT_OF_STOCK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canOrder").value(false));

        assertEquals(availability, availabilityRow(id), "a price edit leaves the closed state, reason, actor and version exactly as they were");
        assertEquals(history, historyCount(id));
    }

    @Test
    void anOpenPublishedProductStaysOrderableAfterAPriceEditAndADraftStaysADraft() throws Exception {
        UUID open = seeded();
        send(patch(BASE + "/" + open + "/publication"), "{\"isPublished\":true,\"version\":0}").andExpect(status().isOk());
        send(patch(BASE + "/" + open), "{\"basePrice\":7.25,\"version\":1}").andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canOrder").value(true));
        UUID draft = seeded();
        send(patch(BASE + "/" + draft), "{\"basePrice\":7.25,\"version\":0}").andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.isPublished").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canOrder").value(false));
    }

    @Test
    void aRejectedPriceEditLeavesTheClosedAvailabilityAndItsHistoryUntouched() throws Exception {
        UUID id = seeded();
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"TEMPORARILY_CLOSED\",\"reasonCode\":\"CLEANING\",\"version\":0}").andExpect(status().isOk());
        var availability = availabilityRow(id);
        int history = historyCount(id);
        var product = row(id);
        send(patch(BASE + "/" + id), "{\"basePrice\":-1,\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"basePrice\":1,\"currency\":\"ZZZ\",\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"basePrice\":1,\"version\":5}").andExpect(status().isConflict());
        assertEquals(product, row(id));
        assertEquals(availability, availabilityRow(id));
        assertEquals(history, historyCount(id));
    }

    // ---- optimistic locking and audit ----

    @Test
    void aStalePriceEditIsAConflictAndTheFirstWritersPriceWins() throws Exception {
        UUID id = seeded();
        send(patch(BASE + "/" + id), "{\"basePrice\":6,\"version\":0}").andExpect(status().isOk());
        send(patch(BASE + "/" + id), "{\"basePrice\":99,\"version\":0}").andExpect(status().isConflict());
        assertEquals(0, new java.math.BigDecimal("6").compareTo((java.math.BigDecimal) row(id).get("base_price")));
        assertEquals(1L, row(id).get("version"));
    }

    @Test
    void aRealPriceChangeBumpsVersionAndTimeAndRecordsTheActorWhileCreatorIsKept() throws Exception {
        UUID id = seeded();
        var created = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN); // a second admin
        send(patch(BASE + "/" + id), "{\"basePrice\":6,\"version\":0}").andExpect(status().isOk());
        var changed = row(id);
        assertEquals(1L, changed.get("version"));
        assertEquals(created.get("created_by_user_id"), changed.get("created_by_user_id"));
        assertNotEquals(created.get("updated_by_user_id"), changed.get("updated_by_user_id"));
        assertEquals(created.get("created_at"), changed.get("created_at"));
        assertTrue(((java.sql.Timestamp) changed.get("updated_at")).after((java.sql.Timestamp) created.get("updated_at")));
    }

    @Test
    void aPriceThatEqualsTheCurrentOneInAnotherScaleIsANoOpWithNoAuditChange() throws Exception {
        UUID id = seeded();
        var created = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        send(patch(BASE + "/" + id), "{\"basePrice\":5.0000,\"vatRate\":10.00,\"currency\":\"TRY\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.version").value(0));
        assertEquals(created, row(id), "nothing, including updated_at and updated_by, moved");
    }
}
