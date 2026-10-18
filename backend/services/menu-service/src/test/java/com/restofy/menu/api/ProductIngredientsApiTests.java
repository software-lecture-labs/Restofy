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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * The optional {@code ingredientsText} of a product through the existing ADMIN endpoints: create, read, list,
 * partial update and clear, its independence from every other field, and the usual security contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductIngredientsApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BASE = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID category;

    @BeforeEach
    void data() {
        category = categories.saveAndFlush(Category.root(RESTAURANT_A, "Main", 0)).getId();
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

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** A JSON string literal; control characters and surrogates travel as \\uXXXX escapes like a real client. */
    private static String json(String text) {
        var out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                default -> out.append(c < 0x20 || Character.isSurrogate(c) ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    private String create(String ingredientsField) {
        return "{\"categoryId\":\"" + category + "\",\"name\":\"Pide\",\"description\":\"Oven baked\"," + ingredientsField
                + "\"basePrice\":12.5,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}";
    }

    private UUID created(String ingredientsField) throws Exception {
        String body = send(post(BASE), create(ingredientsField)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions patchOf(UUID id, String ingredientsJson, long version) throws Exception {
        return send(patch(BASE + "/" + id), "{\"ingredientsText\":" + ingredientsJson + ",\"version\":" + version + "}");
    }

    private ResultActions read(UUID id) throws Exception {
        return mvc.perform(get(BASE + "/" + id).with(user("staff")));
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM products WHERE id = ?", id);
    }

    private int products() {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }

    // ---- create / read / list ----

    @Test
    void createWithIngredientsTextReturnsItOnEveryRead() throws Exception {
        String body = send(post(BASE), create("\"ingredientsText\":\"  Flour, cheese,\\ntomato  \",")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.ingredientsText").value("Flour, cheese,\ntomato"))
                .andExpect(jsonPath("$.description").value("Oven baked"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        read(id).andExpect(status().isOk()).andExpect(jsonPath("$.ingredientsText").value("Flour, cheese,\ntomato"));
        mvc.perform(get(BASE).with(user("staff"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ingredientsText").value("Flour, cheese,\ntomato"));
        assertEquals("Flour, cheese,\ntomato", row(id).get("ingredients_text"));
    }

    @Test
    void createWithoutTheFieldStoresNullAndTheResponseSaysSo() throws Exception {
        UUID id = created("");
        read(id).andExpect(status().isOk()).andExpect(jsonPath("$.ingredientsText").doesNotExist());
        assertNull(row(id).get("ingredients_text"));
        assertTrue(JsonPath.<Map<String, Object>>read(read(id).andReturn().getResponse().getContentAsString(), "$").containsKey("ingredientsText"),
                "the key is always present so a client can tell 'not provided' from 'unknown field'");
    }

    @Test
    void blankOrNullIngredientsAreStoredAsNullNeverAsEmptyText() throws Exception {
        for (String field : new String[] {"\"ingredientsText\":null,", "\"ingredientsText\":\"\",", "\"ingredientsText\":\"   \",", "\"ingredientsText\":\"\\t\\n \","}) {
            UUID id = created(field);
            assertNull(row(id).get("ingredients_text"), field);
        }
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM products WHERE ingredients_text IS NULL", Integer.class));
    }

    @Test
    void unicodeAndLineBreaksAreKept() throws Exception {
        for (String text : new String[] {"Çiğ köfte, İsot, bulgur", "寿司: rice, nori", "Crème fraîche 🍅🧀", "line one\nline two"}) {
            UUID id = created("\"ingredientsText\":" + json(text) + ",");
            assertEquals(text, row(id).get("ingredients_text"));
        }
    }

    @Test
    void lengthBoundaryIs1000Characters() throws Exception {
        created("\"ingredientsText\":" + json("i".repeat(1000)) + ",");
        send(post(BASE), create("\"ingredientsText\":" + json("i".repeat(1001)) + ",")).andExpect(status().isBadRequest());
        assertEquals(1, products());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\u0001b", "a\u001fb", "a\u007fb", "a\u0085b", "a\u009fb", "a\ud800b", "\udc00", "ab\ud83d"})
    void controlCharactersAndBrokenUnicodeAre400OnCreateAndUpdate(String text) throws Exception {
        send(post(BASE), create("\"ingredientsText\":" + json(text) + ",")).andExpect(status().isBadRequest());
        assertEquals(0, products());
        UUID id = created("\"ingredientsText\":\"Flour\",");
        patchOf(id, json(text), 0).andExpect(status().isBadRequest());
        assertEquals("Flour", row(id).get("ingredients_text"));
        assertEquals(0L, row(id).get("version"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "true", "[\"Flour\"]", "{\"v\":\"Flour\"}"})
    void aNonStringIngredientsTextIsA400(String raw) throws Exception {
        send(post(BASE), create("\"ingredientsText\":" + raw + ",")).andExpect(status().isBadRequest());
        UUID id = created("\"ingredientsText\":\"Flour\",");
        patchOf(id, raw, 0).andExpect(status().isBadRequest());
        assertEquals("Flour", row(id).get("ingredients_text"));
    }

    @Test
    void anExistingProductWithoutTheValueReadsAsNullAndIsNotBackfilled() throws Exception {
        UUID id = created("");
        jdbc.update("UPDATE products SET ingredients_text = NULL WHERE id = ?", id);
        read(id).andExpect(jsonPath("$.ingredientsText").doesNotExist()).andExpect(jsonPath("$.name").value("Pide"));
        assertNull(row(id).get("ingredients_text"));
    }

    // ---- update / clear ----

    @Test
    void updateSetsReplacesAndClearsWithoutTouchingOtherFields() throws Exception {
        UUID id = created("");
        var before = row(id);
        patchOf(id, "\"Flour, water\"", 0).andExpect(status().isOk()).andExpect(jsonPath("$.ingredientsText").value("Flour, water"))
                .andExpect(jsonPath("$.version").value(1));
        patchOf(id, "\"Flour, water, salt\"", 1).andExpect(status().isOk()).andExpect(jsonPath("$.ingredientsText").value("Flour, water, salt"))
                .andExpect(jsonPath("$.version").value(2));
        patchOf(id, "\"  \"", 2).andExpect(status().isOk()).andExpect(jsonPath("$.ingredientsText").doesNotExist())
                .andExpect(jsonPath("$.version").value(3));
        var after = row(id);
        assertNull(after.get("ingredients_text"));
        for (String column : List.of("name", "description", "base_price", "currency", "vat_rate", "price_includes_vat", "is_active", "is_published", "category_id")) {
            assertEquals(before.get(column), after.get(column), column + " is untouched by an ingredients update");
        }
    }

    @Test
    void anUpdateWithoutTheFieldOrWithNullLeavesItAsItWas() throws Exception {
        UUID id = created("\"ingredientsText\":\"Flour\",");
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ingredientsText").value("Flour"));
        send(patch(BASE + "/" + id), "{\"ingredientsText\":null,\"description\":\"Hot\",\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ingredientsText").value("Flour"));
        send(patch(BASE + "/" + id), "{\"ingredientsText\":null,\"version\":2}").andExpect(status().isBadRequest());
    }

    @Test
    void anUpdateThatOnlyRepeatsTheCurrentTextIsANoOpWithNoAuditChange() throws Exception {
        UUID id = created("\"ingredientsText\":\"Flour\",");
        var before = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN); // a different admin
        patchOf(id, "\"  Flour  \"", 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        assertEquals(before, row(id), "version, updated_at and updated_by did not move");
    }

    @Test
    void aRealChangeRecordsTheActingAdminAndBumpsTheVersion() throws Exception {
        UUID id = created("");
        var before = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        patchOf(id, "\"Flour\"", 0).andExpect(status().isOk());
        var after = row(id);
        assertEquals(1L, after.get("version"));
        assertNotEquals(before.get("updated_by_user_id"), after.get("updated_by_user_id"));
        assertEquals(before.get("created_by_user_id"), after.get("created_by_user_id"));
    }

    @Test
    void aStaleVersionIsA409AndAnOverlongOrUnsafeTextChangesNothing() throws Exception {
        UUID id = created("\"ingredientsText\":\"Flour\",");
        patchOf(id, "\"Flour, salt\"", 0).andExpect(status().isOk());
        patchOf(id, "\"Stale\"", 0).andExpect(status().isConflict());
        patchOf(id, json("i".repeat(1001)), 1).andExpect(status().isBadRequest());
        assertEquals("Flour, salt", row(id).get("ingredients_text"));
        assertEquals(1L, row(id).get("version"));
    }

    @Test
    void aPartiallyInvalidUpdateAppliesNoneOfItsFields() throws Exception {
        UUID id = created("\"ingredientsText\":\"Flour\",");
        var before = row(id);
        send(patch(BASE + "/" + id), "{\"ingredientsText\":\"New text\",\"name\":\"Renamed\",\"basePrice\":-1,\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"ingredientsText\":\"New text\",\"currency\":\"ZZZ\",\"version\":0}").andExpect(status().isBadRequest());
        assertEquals(before, row(id));
    }

    // ---- independence from the other axes and fields ----

    @Test
    void theTextNeverDerivesOptionsPricesOrAllergenInformation() throws Exception {
        String body = send(post(BASE), create("\"ingredientsText\":\"Gluten, milk, nuts, extra cheese +5 TRY, no onion\",")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> response = JsonPath.read(body, "$");
        for (String forbidden : List.of("options", "ingredients", "allergens", "allergens", "removable", "extras", "priceDelta")) {
            assertFalse(response.containsKey(forbidden), "no derived '" + forbidden + "' in the response");
        }
        assertEquals(12.5, ((Number) response.get("basePrice")).doubleValue(), "extra cheese +5 in the text did not change the price");
        assertEquals("Oven baked", response.get("description"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'product_option%'", Integer.class));
    }

    @Test
    void anIngredientsEditNeverMovesArchivePublicationOrAvailability() throws Exception {
        UUID id = created("");
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true,\"version\":0}").andExpect(status().isOk());
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0}").andExpect(status().isOk());
        var availability = jdbc.queryForMap("SELECT * FROM product_availability WHERE product_id = ?", id);
        int history = jdbc.queryForObject("SELECT count(*) FROM product_availability_history WHERE product_id = ?", Integer.class, id);

        patchOf(id, "\"Flour\"", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(true)).andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.availability.status").value("OUT_OF_STOCK")).andExpect(jsonPath("$.canOrder").value(false));
        assertEquals(availability, jdbc.queryForMap("SELECT * FROM product_availability WHERE product_id = ?", id));
        assertEquals(history, jdbc.queryForObject("SELECT count(*) FROM product_availability_history WHERE product_id = ?", Integer.class, id));
    }

    @Test
    void everyCommandResponseCarriesTheSameIngredientsText() throws Exception {
        UUID id = created("\"ingredientsText\":\"Flour\",");
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true,\"version\":0}").andExpect(jsonPath("$.ingredientsText").value("Flour"));
        send(patch(BASE + "/" + id + "/active"), "{\"isActive\":false,\"version\":1}").andExpect(jsonPath("$.ingredientsText").value("Flour"));
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"TEMPORARILY_CLOSED\",\"reasonCode\":\"CLEANING\",\"version\":0}")
                .andExpect(jsonPath("$.ingredientsText").value("Flour"));
    }

    @Test
    void thePriceVatAndCurrencyContractIsUnchangedByTheNewField() throws Exception {
        send(post(BASE), create("\"ingredientsText\":\"Flour\",").replace("12.5", "0")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.basePrice").value(0));
        send(post(BASE), create("\"ingredientsText\":\"Flour\",").replace("\"TRY\"", "\"XXX\"")).andExpect(status().isBadRequest());
        send(post(BASE), create("\"ingredientsText\":\"Flour\",").replace("12.5", "\"12.5\"")).andExpect(status().isBadRequest());
        send(post(BASE), create("\"ingredientsText\":\"Flour\",").replace("\"vatRate\":10", "\"vatRate\":101")).andExpect(status().isBadRequest());
        assertEquals(1, products());
    }

    // ---- security ----

    @Test
    void anotherRestaurantNeverSeesOrChangesTheText() throws Exception {
        UUID id = created("\"ingredientsText\":\"Secret recipe\",");
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        read(id).andExpect(status().isNotFound());
        patchOf(id, "\"Hijacked\"", 0).andExpect(status().isNotFound());
        String list = mvc.perform(get(BASE).with(user("staff"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("Secret recipe"));
        assertEquals("Secret recipe", row(id).get("ingredients_text"));
    }

    @Test
    void onlyAnAdminMayWriteAndTheAnswerIsDecidedBeforeTheBodyIsValidated() throws Exception {
        UUID id = created("\"ingredientsText\":\"Flour\",");
        String tooLong = json("i".repeat(1001));
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            patchOf(id, "\"Changed\"", 0).andExpect(status().isForbidden());
            patchOf(id, tooLong, 0).andExpect(status().isForbidden());
            send(post(BASE), create("\"ingredientsText\":\"x\",")).andExpect(status().isForbidden());
            read(id).andExpect(status().isForbidden());
        }
        actor.actAsNobody();
        patchOf(id, "\"Changed\"", 0).andExpect(status().isUnauthorized());
        patchOf(id, tooLong, 0).andExpect(status().isUnauthorized());
        read(id).andExpect(status().isUnauthorized());
        assertEquals("Flour", row(id).get("ingredients_text"));
        assertEquals(1, products());
    }

    @Test
    void headersAndBodyFieldsCannotChooseTheTenantOrServerControlledValues() throws Exception {
        UUID forged = UUID.randomUUID();
        String body = create("\"ingredientsText\":\"Flour\",\"id\":\"" + forged + "\",\"createdByUserId\":\"" + forged
                + "\",\"version\":9,\"canOrder\":true,\"isVisibleToCustomer\":true,\"allergens\":[\"milk\"],\"options\":[{\"name\":\"x\"}],");
        String response = mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_B).header("X-Role", "ADMIN").header("X-User-Id", forged).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(response, "$.id"));
        var stored = row(id);
        assertNotEquals(forged, id);
        assertEquals(RESTAURANT_A, stored.get("restaurant_id"));
        assertEquals(0L, stored.get("version"));
        assertNotEquals(forged, stored.get("created_by_user_id"));
        assertEquals(Boolean.FALSE, stored.get("is_published"));
        mvc.perform(patch(BASE + "/" + id).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_B).content("{\"ingredientsText\":\"Y\",\"version\":0,\"restaurantId\":\"" + RESTAURANT_B + "\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("Flour", row(id).get("ingredients_text"));
    }

    @Test
    void errorBodiesDoNotEchoTheSubmittedText() throws Exception {
        String marker = "SECRET-MARKER-";
        String body = send(post(BASE), create("\"ingredientsText\":" + json(marker + "i".repeat(1100)) + ",")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(marker));
        String unsafe = send(post(BASE), create("\"ingredientsText\":" + json(marker + "\u0000") + ",")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertFalse(unsafe.contains(marker));
        assertEquals("Ingredients text contains characters that are not allowed.", JsonPath.read(unsafe, "$.detail"));
    }
}
