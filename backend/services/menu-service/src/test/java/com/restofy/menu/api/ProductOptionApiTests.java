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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** ADMIN management of INCLUDED and ADD options of a product, over HTTP against real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductOptionApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String PRODUCTS = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID categoryA;
    private UUID categoryB;
    private UUID product;

    @BeforeEach
    void data() throws Exception {
        categoryA = categories.saveAndFlush(Category.root(RESTAURANT_A, "Main", 0)).getId();
        categoryB = categories.saveAndFlush(Category.root(RESTAURANT_B, "Main", 0)).getId();
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        product = createProduct(categoryA);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_options");
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM categories");
        actor.actAsNobody();
    }

    // ---- helpers ----

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID createProduct(UUID category) throws Exception {
        String body = send(post(PRODUCTS), "{\"categoryId\":\"" + category + "\",\"name\":\"Pide\",\"description\":\"Hot\","
                + "\"ingredientsText\":\"Flour\",\"basePrice\":50,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String options(UUID productId) {
        return PRODUCTS + "/" + productId + "/options";
    }

    private static String included(String name, boolean removable) {
        return "{\"type\":\"INCLUDED\",\"name\":\"" + name + "\",\"removable\":" + removable + "}";
    }

    private static String extra(String name, String price, int max) {
        return "{\"type\":\"ADD\",\"name\":\"" + name + "\",\"priceDelta\":" + price + ",\"maxQuantity\":" + max + "}";
    }

    private UUID created(String body) throws Exception {
        String json = send(post(options(product)), body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private ResultActions update(UUID optionId, String body) throws Exception {
        return send(patch(options(product) + "/" + optionId), body);
    }

    private Map<String, Object> optionRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM product_options WHERE id = ?", id);
    }

    private Map<String, Object> productRow() {
        return jdbc.queryForMap("SELECT * FROM products WHERE id = ?", product);
    }

    private int optionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM product_options", Integer.class);
    }

    private List<String> names(String query) throws Exception {
        String body = mvc.perform(get(options(product) + query).with(user("staff"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].name");
    }

    // ---- create INCLUDED ----

    @Test
    void aRemovableIncludedIngredientIsCreatedWithMinimumZeroMaximumOneAndNoPrice() throws Exception {
        send(post(options(product)), included("Onion", true)).andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(options(product) + "/")))
                .andExpect(jsonPath("$.type").value("INCLUDED")).andExpect(jsonPath("$.name").value("Onion"))
                .andExpect(jsonPath("$.removable").value(true)).andExpect(jsonPath("$.priceDelta").value(0))
                .andExpect(jsonPath("$.minQuantity").value(0)).andExpect(jsonPath("$.maxQuantity").value(1))
                .andExpect(jsonPath("$.sortOrder").value(0)).andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.productId").value(product.toString()))
                .andExpect(jsonPath("$.createdByUserId").doesNotExist()).andExpect(jsonPath("$.restaurantId").doesNotExist());
        assertEquals(1, optionCount());
    }

    @Test
    void aMandatoryIncludedIngredientHasMinimumOneAndIsNotRemovable() throws Exception {
        send(post(options(product)), included("Dough", false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.removable").value(false)).andExpect(jsonPath("$.minQuantity").value(1))
                .andExpect(jsonPath("$.maxQuantity").value(1));
    }

    @Test
    void anIncludedIngredientMustStateRemovableAndNeverHasAPriceOrQuantity() throws Exception {
        send(post(options(product)), "{\"type\":\"INCLUDED\",\"name\":\"Onion\"}").andExpect(status().isBadRequest());
        send(post(options(product)), "{\"type\":\"INCLUDED\",\"name\":\"Onion\",\"removable\":null}").andExpect(status().isBadRequest());
        send(post(options(product)), "{\"type\":\"INCLUDED\",\"name\":\"Onion\",\"removable\":true,\"priceDelta\":1}").andExpect(status().isBadRequest());
        send(post(options(product)), "{\"type\":\"INCLUDED\",\"name\":\"Onion\",\"removable\":true,\"maxQuantity\":1}").andExpect(status().isBadRequest());
        send(post(options(product)), "{\"type\":\"INCLUDED\",\"name\":\"Onion\",\"removable\":true,\"priceDelta\":0}").andExpect(status().isCreated());
        assertEquals(1, optionCount());
    }

    // ---- create ADD ----

    @Test
    void anExtraIsCreatedWithMinimumZeroTheStatedMaximumAndExactPrice() throws Exception {
        send(post(options(product)), extra("Extra cheese", "12.5", 3)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ADD")).andExpect(jsonPath("$.priceDelta").value(12.5))
                .andExpect(jsonPath("$.minQuantity").value(0)).andExpect(jsonPath("$.maxQuantity").value(3))
                .andExpect(jsonPath("$.removable").doesNotExist());
        assertEquals(new java.math.BigDecimal("12.5000"), jdbc.queryForObject("SELECT price_delta FROM product_options", java.math.BigDecimal.class));
    }

    @Test
    void aFreeExtraAndBothQuantityBoundariesAreValid() throws Exception {
        send(post(options(product)), extra("Ice", "0", 1)).andExpect(status().isCreated()).andExpect(jsonPath("$.priceDelta").value(0));
        send(post(options(product)), extra("Sauce", "0.0001", 10)).andExpect(status().isCreated());
        send(post(options(product)), extra("Gold", "999999999999999.9999", 5)).andExpect(status().isCreated());
        assertEquals(3, optionCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "11", "100", "2147483648"})
    void anExtraMaximumQuantityOutsideOneToTenIsRejected(String max) throws Exception {
        send(post(options(product)), extra("Cheese", "1", 1).replace("\"maxQuantity\":1", "\"maxQuantity\":" + max)).andExpect(status().isBadRequest());
        assertEquals(0, optionCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.0001", "1.00001", "1000000000000000", "\"5\"", "\"abc\"", "true", "[1]", "1e999999999", "\"NaN\""})
    void anInvalidExtraPriceIsRejected(String price) throws Exception {
        send(post(options(product)), extra("Cheese", price, 1)).andExpect(status().isBadRequest());
        assertEquals(0, optionCount());
    }

    @Test
    void anExtraNeedsAPriceAndAMaximumAndCannotBeRemovable() throws Exception {
        send(post(options(product)), "{\"type\":\"ADD\",\"name\":\"Cheese\",\"maxQuantity\":2}").andExpect(status().isBadRequest());
        send(post(options(product)), "{\"type\":\"ADD\",\"name\":\"Cheese\",\"priceDelta\":1}").andExpect(status().isBadRequest());
        send(post(options(product)), "{\"type\":\"ADD\",\"name\":\"Cheese\",\"priceDelta\":1,\"maxQuantity\":2,\"removable\":true}").andExpect(status().isBadRequest());
        send(post(options(product)), "{\"type\":\"ADD\",\"name\":\"Cheese\",\"priceDelta\":null,\"maxQuantity\":2}").andExpect(status().isBadRequest());
        assertEquals(0, optionCount());
    }

    // ---- shared input rules ----

    @ParameterizedTest
    @ValueSource(strings = {"\"included\"", "\"add\"", "\"REMOVE\"", "\"\"", "null", "0", "1", "true", "[\"ADD\"]", "{}"})
    void anUnknownOrWronglyTypedTypeIsRejected(String type) throws Exception {
        send(post(options(product)), "{\"type\":" + type + ",\"name\":\"X\",\"removable\":true,\"priceDelta\":1,\"maxQuantity\":1}").andExpect(status().isBadRequest());
        assertEquals(0, optionCount());
    }

    @Test
    void theNameIsRequiredNormalizedBoundedAndSafe() throws Exception {
        send(post(options(product)), "{\"type\":\"INCLUDED\",\"removable\":true}").andExpect(status().isBadRequest());
        send(post(options(product)), included("   ", true)).andExpect(status().isBadRequest());
        send(post(options(product)), included("n".repeat(151), true)).andExpect(status().isBadRequest());
        send(post(options(product)), included("a\\u0000b", true)).andExpect(status().isBadRequest());
        send(post(options(product)), included("a\\u007fb", true)).andExpect(status().isBadRequest());
        send(post(options(product)), included("a\\ud800b", true)).andExpect(status().isBadRequest());
        send(post(options(product)), "{\"type\":\"INCLUDED\",\"name\":5,\"removable\":true}").andExpect(status().isBadRequest());
        assertEquals(0, optionCount());
        send(post(options(product)), included("  Çiğ \\t köfte 🍅 ", true)).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Çiğ köfte 🍅"));
        send(post(options(product)), included("n".repeat(150), true)).andExpect(status().isCreated());
    }

    @Test
    void sortOrderIsOptionalNonNegativeAndStrictlyNumeric() throws Exception {
        send(post(options(product)), included("A", true).replace("}", ",\"sortOrder\":7}")).andExpect(status().isCreated()).andExpect(jsonPath("$.sortOrder").value(7));
        send(post(options(product)), included("B", true).replace("}", ",\"sortOrder\":-1}")).andExpect(status().isBadRequest());
        send(post(options(product)), included("C", true).replace("}", ",\"sortOrder\":\"3\"}")).andExpect(status().isBadRequest());
        send(post(options(product)), included("D", true).replace("}", ",\"sortOrder\":1.5}")).andExpect(status().isBadRequest());
        send(post(options(product)), included("E", true).replace("}", ",\"sortOrder\":2147483648}")).andExpect(status().isBadRequest());
        assertEquals(1, optionCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "\"true\"", "\"yes\"", "[]"})
    void removableMustBeARealBoolean(String raw) throws Exception {
        send(post(options(product)), "{\"type\":\"INCLUDED\",\"name\":\"Onion\",\"removable\":" + raw + "}").andExpect(status().isBadRequest());
        assertEquals(0, optionCount());
    }

    @Test
    void serverControlledValuesInTheBodyAreNeverAssigned() throws Exception {
        UUID forged = UUID.randomUUID();
        String body = included("Onion", true).replace("{", "{\"id\":\"" + forged + "\",\"version\":9,\"isActive\":false,\"createdByUserId\":\""
                + forged + "\",\"minQuantity\":5,\"createdAt\":\"2001-01-01T00:00:00Z\",\"updatedByUserId\":\"" + forged + "\",");
        String json = send(post(options(product)), body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        assertNotEquals(forged, id);
        var row = optionRow(id);
        assertEquals(0L, row.get("version"));
        assertEquals(Boolean.TRUE, row.get("is_active"));
        assertNotEquals(forged, row.get("created_by_user_id"));
        assertEquals(1, row.get("max_quantity"));
        assertEquals(RESTAURANT_A, row.get("restaurant_id"));
        assertEquals(product, row.get("product_id"));
    }

    @Test
    void theRestaurantAndTheProductCannotBeSetInTheBody() throws Exception {
        send(post(options(product)), included("Onion", true).replace("{", "{\"restaurantId\":\"" + RESTAURANT_B + "\",")).andExpect(status().isBadRequest());
        send(post(options(product)), included("Onion", true).replace("{", "{\"productId\":\"" + UUID.randomUUID() + "\",")).andExpect(status().isBadRequest());
        assertEquals(0, optionCount());
    }

    // ---- uniqueness ----

    @Test
    void theSameTypeAndNormalizedNameIsA409EvenWhenTheExistingOptionIsDisabled() throws Exception {
        UUID cheese = created(included("Cheese", true));
        send(post(options(product)), included("  CHEESE ", false)).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        update(cheese, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        send(post(options(product)), included("cheese", true)).andExpect(status().isConflict());
        assertEquals(1, optionCount());
    }

    @Test
    void theSameNameIsAllowedOnceAsIncludedAndOnceAsAnExtraOnAnotherProductAndInAnotherRestaurant() throws Exception {
        created(included("Cheese", true));
        created(extra("Cheese", "5", 2));
        send(post(options(product)), extra("cheese", "5", 2)).andExpect(status().isConflict());
        UUID second = createProduct(categoryA);
        send(post(options(second)), included("Cheese", true)).andExpect(status().isCreated());
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        UUID foreign = createProduct(categoryB);
        send(post(options(foreign)), included("Cheese", true)).andExpect(status().isCreated());
    }

    // ---- update, soft-disable, re-enable ----

    @Test
    void anExtraCanBeRenamedRepricedResizedAndReorderedWithTheVersionAdvancing() throws Exception {
        UUID id = created(extra("Cheese", "5", 2));
        update(id, "{\"name\":\"Cheddar\",\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cheddar")).andExpect(jsonPath("$.version").value(1));
        update(id, "{\"priceDelta\":0,\"maxQuantity\":10,\"sortOrder\":3,\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.priceDelta").value(0)).andExpect(jsonPath("$.maxQuantity").value(10))
                .andExpect(jsonPath("$.sortOrder").value(3)).andExpect(jsonPath("$.version").value(2));
        var row = optionRow(id);
        assertEquals("ADD", row.get("option_type"));
        assertEquals(0, row.get("min_quantity"));
    }

    @Test
    void anIncludedIngredientCanSwitchBetweenRemovableAndMandatory() throws Exception {
        UUID id = created(included("Onion", true));
        update(id, "{\"removable\":false,\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.removable").value(false)).andExpect(jsonPath("$.minQuantity").value(1));
        update(id, "{\"removable\":true,\"version\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.removable").value(true)).andExpect(jsonPath("$.minQuantity").value(0));
    }

    @Test
    void fieldsThatDoNotBelongToTheTypeAreRejectedOnUpdate() throws Exception {
        UUID onion = created(included("Onion", true));
        UUID cheese = created(extra("Cheese", "5", 2));
        update(onion, "{\"maxQuantity\":2,\"version\":0}").andExpect(status().isBadRequest());
        update(onion, "{\"priceDelta\":1,\"version\":0}").andExpect(status().isBadRequest());
        update(cheese, "{\"removable\":true,\"version\":0}").andExpect(status().isBadRequest());
        update(cheese, "{\"maxQuantity\":11,\"version\":0}").andExpect(status().isBadRequest());
        update(cheese, "{\"priceDelta\":-1,\"version\":0}").andExpect(status().isBadRequest());
        assertEquals(0L, optionRow(onion).get("version"));
        assertEquals(0L, optionRow(cheese).get("version"));
    }

    @Test
    void aZeroPriceOnAnIncludedIngredientIsAHarmlessNoOp() throws Exception {
        UUID onion = created(included("Onion", true));
        update(onion, "{\"priceDelta\":0,\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void theTypeCanNeverChange() throws Exception {
        UUID id = created(included("Onion", true));
        update(id, "{\"type\":\"ADD\",\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"type\":\"INCLUDED\",\"name\":\"X\",\"version\":0}").andExpect(status().isBadRequest());
        assertEquals("INCLUDED", optionRow(id).get("option_type"));
        assertEquals("Onion", optionRow(id).get("name"));
    }

    @Test
    void anUpdateNeedsAFieldAndAVersionAndTheVersionMustBeAWholeNumber() throws Exception {
        UUID id = created(included("Onion", true));
        update(id, "{\"version\":0}").andExpect(status().isBadRequest());
        update(id, "{\"name\":\"X\"}").andExpect(status().isBadRequest());
        update(id, "{\"name\":\"X\",\"version\":-1}").andExpect(status().isBadRequest());
        update(id, "{\"name\":\"X\",\"version\":\"0\"}").andExpect(status().isBadRequest());
        update(id, "{\"name\":\"X\",\"version\":0.9}").andExpect(status().isBadRequest());
        update(id, "{\"name\":\"X\",\"version\":null}").andExpect(status().isBadRequest());
        assertEquals("Onion", optionRow(id).get("name"));
    }

    @Test
    void aStaleVersionIsA409AndNothingChanges() throws Exception {
        UUID id = created(included("Onion", true));
        update(id, "{\"name\":\"Red onion\",\"version\":0}").andExpect(status().isOk());
        update(id, "{\"name\":\"Stale\",\"version\":0}").andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        update(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isConflict());
        assertEquals("Red onion", optionRow(id).get("name"));
        assertEquals(Boolean.TRUE, optionRow(id).get("is_active"));
    }

    @Test
    void repeatingTheCurrentValuesIsANoOpWithoutAVersionBumpOrAuditChange() throws Exception {
        UUID id = created(extra("Cheese", "5", 2));
        var before = optionRow(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        update(id, "{\"name\":\" Cheese \",\"priceDelta\":5.0000,\"maxQuantity\":2,\"sortOrder\":0,\"isActive\":true,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        assertEquals(before, optionRow(id));
    }

    @Test
    void aRealChangeRecordsTheActingAdminAndKeepsTheCreator() throws Exception {
        UUID id = created(included("Onion", true));
        var before = optionRow(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        update(id, "{\"sortOrder\":5,\"version\":0}").andExpect(status().isOk());
        var after = optionRow(id);
        assertEquals(1L, after.get("version"));
        assertNotEquals(before.get("updated_by_user_id"), after.get("updated_by_user_id"));
        assertEquals(before.get("created_by_user_id"), after.get("created_by_user_id"));
        assertTrue(((java.sql.Timestamp) after.get("updated_at")).after((java.sql.Timestamp) before.get("updated_at")));
    }

    @Test
    void renamingIntoAnExistingNameIsA409AndKeepsTheOldName() throws Exception {
        created(extra("Cheese", "5", 2));
        UUID sauce = created(extra("Sauce", "2", 2));
        update(sauce, "{\"name\":\"CHEESE\",\"version\":0}").andExpect(status().isConflict());
        assertEquals("Sauce", optionRow(sauce).get("name"));
        assertEquals(0L, optionRow(sauce).get("version"));
    }

    @Test
    void anOptionIsDisabledNotDeletedAndCanBeEnabledAgain() throws Exception {
        UUID id = created(extra("Cheese", "5", 2));
        update(id, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.isActive").value(false));
        assertEquals(1, optionCount(), "the row survives");
        assertEquals(List.of("Cheese"), names("?isActive=false"));
        assertEquals(List.of(), names("?isActive=true"));
        update(id, "{\"isActive\":true,\"version\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.isActive").value(true));
        assertEquals(List.of("Cheese"), names("?isActive=true"));
    }

    @Test
    void thereIsNoDeleteEndpoint() throws Exception {
        UUID id = created(included("Onion", true));
        mvc.perform(delete(options(product) + "/" + id).with(user("staff")).with(csrf())).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(options(product)).with(user("staff")).with(csrf())).andExpect(status().isMethodNotAllowed());
        assertEquals(1, optionCount());
    }

    // ---- read and list ----

    @Test
    void getOneReturnsTheOptionAndAnOptionOfAnotherProductIsNotFound() throws Exception {
        UUID id = created(extra("Cheese", "5", 2));
        mvc.perform(get(options(product) + "/" + id).with(user("staff"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.type").value("ADD"));
        UUID second = createProduct(categoryA);
        mvc.perform(get(options(second) + "/" + id).with(user("staff"))).andExpect(status().isNotFound());
        send(patch(options(second) + "/" + id), "{\"name\":\"Hijack\",\"version\":0}").andExpect(status().isNotFound());
        assertEquals("Cheese", optionRow(id).get("name"));
    }

    @Test
    void theListIsIncludedFirstThenExtrasThenSortOrderThenNameThenId() throws Exception {
        created(extra("zeta", "1", 1));
        created(included("Tomato", true).replace("}", ",\"sortOrder\":2}"));
        created(included("onion", true).replace("}", ",\"sortOrder\":2}"));
        created(included("Bread", false).replace("}", ",\"sortOrder\":1}"));
        created(extra("Alpha", "1", 1).replace("}", ",\"sortOrder\":5}"));
        created(extra("beta", "1", 1).replace("}", ",\"sortOrder\":5}"));
        assertEquals(List.of("Bread", "onion", "Tomato", "zeta", "Alpha", "beta"), names(""));
    }

    @Test
    void equalSortKeysAreOrderedByIdSoTheListIsRepeatable() throws Exception {
        for (int i = 0; i < 6; i++) {
            created(extra("Item " + i, "1", 1));
        }
        // Names are unique per product and type, so the id tie-breaker only matters across equal keys; here the point
        // is that repeating the request returns exactly the same order.
        String first = mvc.perform(get(options(product) + "?size=100").with(user("staff"))).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get(options(product) + "?size=100").with(user("staff"))).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals(List.of("Item 0", "Item 1", "Item 2", "Item 3", "Item 4", "Item 5"), JsonPath.<List<String>>read(first, "$.items[*].name"));
    }

    @Test
    void typeAndActiveFiltersAndPagingWork() throws Exception {
        UUID cheese = created(extra("Cheese", "5", 2));
        created(extra("Sauce", "2", 2));
        created(included("Onion", true));
        created(included("Bread", false));
        update(cheese, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        assertEquals(List.of("Bread", "Onion"), names("?type=INCLUDED"));
        assertEquals(List.of("Cheese", "Sauce"), names("?type=ADD"));
        assertEquals(List.of("Bread", "Onion", "Sauce"), names("?isActive=true"));
        assertEquals(List.of("Cheese"), names("?isActive=false&type=ADD"));

        List<String> collected = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            String body = mvc.perform(get(options(product) + "?size=2&page=" + page).with(user("staff"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4)).andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.size").value(2))
                    .andReturn().getResponse().getContentAsString();
            collected.addAll(JsonPath.<List<String>>read(body, "$.items[*].name"));
        }
        assertEquals(List.of("Bread", "Onion", "Cheese", "Sauce"), collected);
        assertEquals(List.of(), names("?size=2&page=2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?size=0", "?size=101", "?size=abc", "?page=-1", "?type=REMOVE", "?type=add", "?type=0", "?isActive=maybe", "?sort=name"})
    void invalidListParametersAreRejectedOrIgnoredSafely(String query) throws Exception {
        var result = mvc.perform(get(options(product) + query).with(user("staff"))).andReturn().getResponse();
        if (query.equals("?sort=name")) {
            assertEquals(200, result.getStatus(), "an unknown parameter never reaches the query");
        } else {
            assertEquals(400, result.getStatus(), query);
        }
    }

    @Test
    void aProductWithoutOptionsHasAnEmptyPageButAForeignOrMissingProductIsNotFound() throws Exception {
        mvc.perform(get(options(product)).with(user("staff"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(options(UUID.randomUUID())).with(user("staff"))).andExpect(status().isNotFound());
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        mvc.perform(get(options(product)).with(user("staff"))).andExpect(status().isNotFound());
    }

    // ---- the product is never touched ----

    @Test
    void creatingUpdatingAndDisablingOptionsLeavesTheWholeProductExactlyAsItWas() throws Exception {
        send(patch(PRODUCTS + "/" + product + "/publication"), "{\"isPublished\":true,\"version\":0}").andExpect(status().isOk());
        send(patch(PRODUCTS + "/" + product), "{\"imageUrl\":\"\",\"description\":\"Hot\",\"version\":1}").andExpect(status().isOk());
        var before = productRow();
        var availability = jdbc.queryForMap("SELECT * FROM product_availability WHERE product_id = ?", product);

        UUID cheese = created(extra("Cheese", "5", 2));
        created(included("Onion", true));
        update(cheese, "{\"priceDelta\":9,\"version\":0}").andExpect(status().isOk());
        update(cheese, "{\"isActive\":false,\"version\":1}").andExpect(status().isOk());

        assertEquals(before, productRow(), "price, VAT, category, publication, ingredients text, image, version and audit are untouched");
        assertEquals(availability, jdbc.queryForMap("SELECT * FROM product_availability WHERE product_id = ?", product));
    }

    @Test
    void theProductResponseDoesNotEmbedOptions() throws Exception {
        created(included("Onion", true));
        String body = mvc.perform(get(PRODUCTS + "/" + product).with(user("staff"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> response = JsonPath.read(body, "$");
        assertFalse(response.containsKey("options"));
        assertFalse(response.containsKey("ingredients"));
    }

    @Test
    void anArchivedProductsOptionsStayManageable() throws Exception {
        send(patch(PRODUCTS + "/" + product + "/active"), "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        created(included("Onion", true));
        assertEquals(List.of("Onion"), names(""));
    }

    @Test
    void changingTheProductCurrencyIsNotBlockedAndLeavesTheOptionPriceNumberAsItWas() throws Exception {
        // Documented open decision: an option price is read in the product's currency, so a currency change
        // re-reads the same number in the new currency. The product update contract is deliberately unchanged.
        UUID cheese = created(extra("Cheese", "5", 2));
        send(patch(PRODUCTS + "/" + product), "{\"currency\":\"EUR\",\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("EUR"));
        mvc.perform(get(options(product) + "/" + cheese).with(user("staff"))).andExpect(jsonPath("$.priceDelta").value(5));
    }

    // ---- limits ----

    private void insertActive(int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO product_options (id, restaurant_id, product_id, name, option_type, price_delta, min_quantity,
                                                 max_quantity, sort_order, created_by_user_id, updated_by_user_id)
                    VALUES (?, ?, ?, ?, 'INCLUDED', 0, 0, 1, 0, ?, ?)""", UUID.randomUUID(), RESTAURANT_A, product, "Bulk " + i,
                    UUID.randomUUID(), UUID.randomUUID());
        }
    }

    @Test
    void atMostFiftyActiveOptionsPerProductAndDisabledOnesDoNotCount() throws Exception {
        insertActive(49);
        UUID fiftieth = created(extra("Fiftieth", "1", 1));
        send(post(options(product)), extra("Fifty first", "1", 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A product can have at most 50 active options."));
        update(fiftieth, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        send(post(options(product)), extra("Fifty first", "1", 1)).andExpect(status().isCreated());
        assertEquals(51, optionCount(), "51 rows, 50 of them active");
        assertEquals(50, jdbc.queryForObject("SELECT count(*) FROM product_options WHERE is_active", Integer.class));
    }

    @Test
    void reEnablingIsRefusedWhenFiftyAreAlreadyActiveButRenamingADisabledOneIsNot() throws Exception {
        insertActive(49);
        UUID spare = created(extra("Spare", "1", 1));
        update(spare, "{\"isActive\":false,\"version\":0}").andExpect(status().isOk());
        created(extra("Replacement", "1", 1));
        update(spare, "{\"isActive\":true,\"version\":1}").andExpect(status().isConflict());
        update(spare, "{\"name\":\"Renamed spare\",\"version\":1}").andExpect(status().isOk());
        assertEquals(50, jdbc.queryForObject("SELECT count(*) FROM product_options WHERE is_active", Integer.class));
    }

    @Test
    void manyDisabledOptionsNeverMakeTheManagementListGrowWithoutBound() throws Exception {
        insertActive(30);
        jdbc.update("UPDATE product_options SET is_active = FALSE");
        String body = mvc.perform(get(options(product)).with(user("staff"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(30, ((List<?>) JsonPath.read(body, "$.items")).size());
        mvc.perform(get(options(product) + "?size=10").with(user("staff"))).andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get(options(product) + "?size=100").with(user("staff"))).andExpect(status().isOk());
        mvc.perform(get(options(product) + "?size=101").with(user("staff"))).andExpect(status().isBadRequest());
    }

    // ---- security ----

    @Test
    void anotherRestaurantCanNeitherSeeCreateNorChangeMyOptions() throws Exception {
        UUID id = created(extra("Cheese", "5", 2));
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        mvc.perform(get(options(product) + "/" + id).with(user("staff"))).andExpect(status().isNotFound());
        send(post(options(product)), included("Intruder", true)).andExpect(status().isNotFound());
        update(id, "{\"name\":\"Hijack\",\"version\":0}").andExpect(status().isNotFound());
        assertEquals("Cheese", optionRow(id).get("name"));
        assertEquals(1, optionCount());
    }

    @Test
    void onlyAnAdminMayManageOptionsAndTheAnswerComesBeforeTheBodyIsValidated() throws Exception {
        UUID id = created(included("Onion", true));
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            send(post(options(product)), included("Intruder", true)).andExpect(status().isForbidden());
            send(post(options(product)), "{}").andExpect(status().isForbidden());
            update(id, "{\"name\":\"Changed\",\"version\":0}").andExpect(status().isForbidden());
            update(id, "{}").andExpect(status().isForbidden());
            mvc.perform(get(options(product)).with(user("staff"))).andExpect(status().isForbidden());
            mvc.perform(get(options(product) + "/" + id).with(user("staff"))).andExpect(status().isForbidden());
        }
        actor.actAsNobody();
        send(post(options(product)), included("Intruder", true)).andExpect(status().isUnauthorized());
        send(post(options(product)), "{not json").andExpect(status().isUnauthorized());
        update(id, "{}").andExpect(status().isUnauthorized());
        mvc.perform(get(options(product) + "?size=0").with(user("staff"))).andExpect(status().isUnauthorized());
        mvc.perform(get(options(product))).andExpect(status().isUnauthorized());
        assertEquals("Onion", optionRow(id).get("name"));
        assertEquals(1, optionCount());
    }

    @Test
    void identityHeadersCannotChooseTheTenantOrTheRole() throws Exception {
        UUID id = created(included("Onion", true));
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        mvc.perform(patch(options(product) + "/" + id).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_A).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID())
                        .content("{\"name\":\"Hijack\",\"version\":0}"))
                .andExpect(status().isNotFound());
        actor.actAs(RESTAURANT_A, ActorRole.WAITER);
        mvc.perform(post(options(product)).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Role", "ADMIN").content(included("Intruder", true)))
                .andExpect(status().isForbidden());
        assertEquals("Onion", optionRow(id).get("name"));
    }

    @Test
    void errorBodiesAreFixedTextWithoutTheSubmittedValueOrInternals() throws Exception {
        String marker = "SECRET-MARKER-";
        String bad = send(post(options(product)), included(marker + "\\u0000", true)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(bad.contains(marker));
        created(included("Cheese", true));
        String dup = send(post(options(product)), included("Cheese", true)).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        for (String leak : List.of("uq_product_options_name", "exception", "sql", "constraint", "org.springframework")) {
            assertFalse(dup.toLowerCase().contains(leak), leak);
        }
        String missing = send(patch(options(product) + "/" + UUID.randomUUID()), "{\"name\":\"x\",\"version\":0}").andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertEquals("Product option was not found.", JsonPath.read(missing, "$.detail"));
    }
}
