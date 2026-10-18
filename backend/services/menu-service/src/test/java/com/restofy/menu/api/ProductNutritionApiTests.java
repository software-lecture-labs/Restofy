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
import org.junit.jupiter.params.provider.CsvSource;
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

import java.math.BigDecimal;
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
 * Calories, weight and estimated preparation time through the existing ADMIN product endpoints: create, read,
 * list, partial update (keep / clear / set), exact boundaries and decimals, strict JSON types, no-op behaviour
 * and the unchanged security contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductNutritionApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BASE = "/api/v1/menu/products";
    private static final List<String> OTHER_COLUMNS = List.of("name", "description", "ingredients_text", "image_url", "base_price",
            "currency", "vat_rate", "price_includes_vat", "is_active", "is_published", "category_id");

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

    private String create(String nutritionFields) {
        return "{\"categoryId\":\"" + category + "\",\"name\":\"Pide\",\"description\":\"Oven baked\"," + nutritionFields
                + "\"basePrice\":12.5,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}";
    }

    private UUID created(String nutritionFields) throws Exception {
        String body = send(post(BASE), create(nutritionFields)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions patchOf(UUID id, String fields, long version) throws Exception {
        return send(patch(BASE + "/" + id), "{" + fields + (fields.isEmpty() ? "" : ",") + "\"version\":" + version + "}");
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

    // ---- valid values: create / read / list ----

    @Test
    void createWithAllThreeFieldsReturnsThemOnEveryRead() throws Exception {
        String body = send(post(BASE), create("\"calories\":250,\"weightGrams\":12.345,\"estimatedPrepMinutes\":8,"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calories").value(250))
                .andExpect(jsonPath("$.weightGrams").value(12.345))
                .andExpect(jsonPath("$.estimatedPrepMinutes").value(8))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        read(id).andExpect(status().isOk()).andExpect(jsonPath("$.calories").value(250))
                .andExpect(jsonPath("$.weightGrams").value(12.345)).andExpect(jsonPath("$.estimatedPrepMinutes").value(8));
        mvc.perform(get(BASE).with(user("staff"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].calories").value(250))
                .andExpect(jsonPath("$.items[0].weightGrams").value(12.345))
                .andExpect(jsonPath("$.items[0].estimatedPrepMinutes").value(8));
        var stored = row(id);
        assertEquals(250, stored.get("calories"));
        assertEquals(new BigDecimal("12.345"), stored.get("weight_grams"));
        assertEquals(8, stored.get("estimated_prep_minutes"));
    }

    @Test
    void createWithoutTheFieldsStoresNullAndEveryResponseKeepsTheKeys() throws Exception {
        UUID id = created("");
        var stored = row(id);
        assertNull(stored.get("calories"));
        assertNull(stored.get("weight_grams"));
        assertNull(stored.get("estimated_prep_minutes"));
        String body = read(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> json = JsonPath.read(body, "$");
        for (String key : List.of("calories", "weightGrams", "estimatedPrepMinutes")) {
            assertTrue(json.containsKey(key), key + " is always present");
            assertNull(json.get(key), key + " is null when unknown");
        }
        mvc.perform(get(BASE).with(user("staff"))).andExpect(jsonPath("$.items[0].calories").value((Object) null));
    }

    @Test
    void createWithExplicitNullsIsTheSameAsOmitting() throws Exception {
        UUID id = created("\"calories\":null,\"weightGrams\":null,\"estimatedPrepMinutes\":null,");
        var stored = row(id);
        assertNull(stored.get("calories"));
        assertNull(stored.get("weight_grams"));
        assertNull(stored.get("estimated_prep_minutes"));
    }

    @Test
    void onlyTheSuppliedFieldIsStoredOnCreate() throws Exception {
        UUID id = created("\"estimatedPrepMinutes\":15,");
        assertEquals(15, row(id).get("estimated_prep_minutes"));
        assertNull(row(id).get("calories"));
        assertNull(row(id).get("weight_grams"));
    }

    @Test
    void zeroCaloriesAndZeroMinutesAreRealValuesNotUnknown() throws Exception {
        UUID id = created("\"calories\":0,\"estimatedPrepMinutes\":0,");
        read(id).andExpect(jsonPath("$.calories").value(0)).andExpect(jsonPath("$.estimatedPrepMinutes").value(0))
                .andExpect(jsonPath("$.weightGrams").value((Object) null));
    }

    // ---- boundary values ----

    @ParameterizedTest
    @CsvSource({"0,0.001,0", "10000,10000,240", "1,0.1,1", "9999,9999.999,239"})
    void boundaryValuesAreAcceptedOnCreateAndUpdate(String calories, String weight, String minutes) throws Exception {
        UUID id = created("\"calories\":" + calories + ",\"weightGrams\":" + weight + ",\"estimatedPrepMinutes\":" + minutes + ",");
        assertEquals(Integer.parseInt(calories), row(id).get("calories"));
        assertEquals(0, new BigDecimal(weight).compareTo((BigDecimal) row(id).get("weight_grams")));
        assertEquals(Integer.parseInt(minutes), row(id).get("estimated_prep_minutes"));
        UUID other = created("");
        patchOf(other, "\"calories\":" + calories + ",\"weightGrams\":" + weight + ",\"estimatedPrepMinutes\":" + minutes, 0)
                .andExpect(status().isOk());
        assertEquals(Integer.parseInt(calories), row(other).get("calories"));
    }

    @ParameterizedTest
    @CsvSource({"calories,-1", "calories,10001", "calories,2147483647", "estimatedPrepMinutes,-1", "estimatedPrepMinutes,241",
            "weightGrams,0", "weightGrams,0.0", "weightGrams,0.000", "weightGrams,-0.001", "weightGrams,-5", "weightGrams,10000.001", "weightGrams,10001"})
    void outOfRangeValuesAre400OnCreateAndUpdateAndChangeNothing(String field, String value) throws Exception {
        send(post(BASE), create("\"" + field + "\":" + value + ",")).andExpect(status().isBadRequest());
        assertEquals(0, products());
        UUID id = created("");
        var before = row(id);
        patchOf(id, "\"" + field + "\":" + value, 0).andExpect(status().isBadRequest());
        assertEquals(before, row(id));
    }

    // ---- invalid values / types / precision ----

    @ParameterizedTest
    @ValueSource(strings = {"\"250\"", "\"\"", "true", "false", "[250]", "[]", "{}", "{\"v\":250}", "250.0", "2.5e2", "1E2", "250.5",
            "99999999999", "99999999999999999999999"})
    void caloriesAndMinutesAcceptOnlyAWholeJsonNumber(String raw) throws Exception {
        for (String field : new String[] {"calories", "estimatedPrepMinutes"}) {
            send(post(BASE), create("\"" + field + "\":" + raw + ",")).andExpect(status().isBadRequest());
            UUID id = created("\"" + field + "\":5,");
            patchOf(id, "\"" + field + "\":" + raw, 0).andExpect(status().isBadRequest());
            assertEquals(5, row(id).get(field.equals("calories") ? "calories" : "estimated_prep_minutes"));
            assertEquals(0L, row(id).get("version"));
        }
        assertEquals(2, products());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"12.5\"", "\"\"", "true", "[12.5]", "{}", "{\"v\":1}"})
    void theWeightAcceptsOnlyAJsonNumber(String raw) throws Exception {
        send(post(BASE), create("\"weightGrams\":" + raw + ",")).andExpect(status().isBadRequest());
        UUID id = created("\"weightGrams\":5,");
        patchOf(id, "\"weightGrams\":" + raw, 0).andExpect(status().isBadRequest());
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) row(id).get("weight_grams")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.3456", "12.3450", "0.0001", "0.0005", "1.0000", "250.0000", "1E-4", "99.99999"})
    void extraDecimalDigitsAreRefusedNeverRoundedOrCut(String raw) throws Exception {
        send(post(BASE), create("\"weightGrams\":" + raw + ",")).andExpect(status().isBadRequest());
        UUID id = created("\"weightGrams\":12.345,");
        patchOf(id, "\"weightGrams\":" + raw, 0).andExpect(status().isBadRequest());
        assertEquals(new BigDecimal("12.345"), row(id).get("weight_grams"), "the stored weight did not move");
        assertEquals(0, products() - 1);
    }

    @ParameterizedTest
    @CsvSource({"12.345,12.345", "12.5,12.5", "12.50,12.5", "250,250", "250.0,250", "2.5E+2,250", "0.001,0.001", "10000,10000", "10000.000,10000", "0.100,0.1"})
    void validDecimalNotationsAreAcceptedAndShownWithoutTrailingZeros(String sent, String shown) throws Exception {
        UUID id = created("\"weightGrams\":" + sent + ",");
        String body = read(id).andReturn().getResponse().getContentAsString();
        assertEquals(0, new BigDecimal(shown).compareTo(new BigDecimal(JsonPath.<Object>read(body, "$.weightGrams").toString())));
        assertFalse(body.contains("\"weightGrams\":" + shown + "0"), "no trailing zero: " + body);
        assertFalse(body.contains("E+"), "never an exponent: " + body);
        assertTrue(body.contains("\"weightGrams\":" + shown + ","), body);
    }

    @Test
    void aFifthDecimalDigitIsRefusedEvenWhenItWouldRoundToTheStoredValue() throws Exception {
        UUID id = created("\"weightGrams\":12.345,");
        patchOf(id, "\"weightGrams\":12.34500", 0).andExpect(status().isBadRequest());
        assertEquals(0L, row(id).get("version"));
    }

    @Test
    void anErrorNeverEchoesTheRejectedValue() throws Exception {
        String body = send(post(BASE), create("\"calories\":987654,")).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("987654"), body);
        body = send(post(BASE), create("\"weightGrams\":\"secret-text\",")).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("secret-text"), body);
    }

    // ---- PATCH: keep / clear / set / no-op ----

    @Test
    void anUpdateSetsEachFieldIndependently() throws Exception {
        UUID id = created("");
        var before = row(id);
        patchOf(id, "\"calories\":300", 0).andExpect(status().isOk()).andExpect(jsonPath("$.calories").value(300))
                .andExpect(jsonPath("$.weightGrams").value((Object) null)).andExpect(jsonPath("$.version").value(1));
        patchOf(id, "\"weightGrams\":99.5", 1).andExpect(status().isOk()).andExpect(jsonPath("$.calories").value(300))
                .andExpect(jsonPath("$.weightGrams").value(99.5)).andExpect(jsonPath("$.version").value(2));
        patchOf(id, "\"estimatedPrepMinutes\":12", 2).andExpect(status().isOk()).andExpect(jsonPath("$.estimatedPrepMinutes").value(12))
                .andExpect(jsonPath("$.calories").value(300)).andExpect(jsonPath("$.weightGrams").value(99.5)).andExpect(jsonPath("$.version").value(3));
        var after = row(id);
        for (String column : OTHER_COLUMNS) {
            assertEquals(before.get(column), after.get(column), column + " is untouched by a nutrition update");
        }
    }

    @Test
    void fieldsThatAreNotSentKeepTheirValue() throws Exception {
        UUID id = created("\"calories\":250,\"weightGrams\":12.5,\"estimatedPrepMinutes\":8,");
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.calories").value(250)).andExpect(jsonPath("$.weightGrams").value(12.5))
                .andExpect(jsonPath("$.estimatedPrepMinutes").value(8));
        patchOf(id, "\"calories\":260", 1).andExpect(status().isOk()).andExpect(jsonPath("$.calories").value(260))
                .andExpect(jsonPath("$.weightGrams").value(12.5)).andExpect(jsonPath("$.estimatedPrepMinutes").value(8));
    }

    @Test
    void anExplicitNullClearsOnlyThatField() throws Exception {
        UUID id = created("\"calories\":250,\"weightGrams\":12.5,\"estimatedPrepMinutes\":8,");
        patchOf(id, "\"weightGrams\":null", 0).andExpect(status().isOk()).andExpect(jsonPath("$.weightGrams").value((Object) null))
                .andExpect(jsonPath("$.calories").value(250)).andExpect(jsonPath("$.estimatedPrepMinutes").value(8))
                .andExpect(jsonPath("$.version").value(1));
        assertNull(row(id).get("weight_grams"));
        patchOf(id, "\"calories\":null,\"estimatedPrepMinutes\":null", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.calories").value((Object) null)).andExpect(jsonPath("$.estimatedPrepMinutes").value((Object) null))
                .andExpect(jsonPath("$.version").value(2));
        assertNull(row(id).get("calories"));
        assertNull(row(id).get("estimated_prep_minutes"));
    }

    @Test
    void clearingAndSettingZeroAreDifferent() throws Exception {
        UUID id = created("\"calories\":250,");
        patchOf(id, "\"calories\":0", 0).andExpect(status().isOk()).andExpect(jsonPath("$.calories").value(0));
        assertEquals(0, row(id).get("calories"));
        patchOf(id, "\"calories\":null", 1).andExpect(status().isOk());
        assertNull(row(id).get("calories"));
    }

    @Test
    void clearingAnAlreadyUnknownValueIsANoOpWithNoAuditChange() throws Exception {
        UUID id = created("");
        var before = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN); // a different admin
        patchOf(id, "\"calories\":null,\"weightGrams\":null,\"estimatedPrepMinutes\":null", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        assertEquals(before, row(id), "version, updated_at and updated_by did not move");
    }

    @Test
    void repeatingTheCurrentValuesIsANoOpWithNoAuditChange() throws Exception {
        UUID id = created("\"calories\":250,\"weightGrams\":12.5,\"estimatedPrepMinutes\":8,");
        var before = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        patchOf(id, "\"calories\":250,\"weightGrams\":12.500,\"estimatedPrepMinutes\":8", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        patchOf(id, "\"weightGrams\":1.25E+1", 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        assertEquals(before, row(id));
    }

    @Test
    void aRealChangeRecordsTheActingAdminAndBumpsTheVersionOnce() throws Exception {
        UUID id = created("");
        var before = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        patchOf(id, "\"calories\":1,\"weightGrams\":1,\"estimatedPrepMinutes\":1", 0).andExpect(status().isOk());
        var after = row(id);
        assertEquals(1L, after.get("version"));
        assertNotEquals(before.get("updated_by_user_id"), after.get("updated_by_user_id"));
        assertEquals(before.get("created_by_user_id"), after.get("created_by_user_id"));
    }

    @Test
    void aMixedUpdateWithOneRealChangeBumpsTheVersionOnce() throws Exception {
        UUID id = created("\"calories\":250,");
        patchOf(id, "\"calories\":250,\"estimatedPrepMinutes\":9", 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void nutritionCombinesWithOtherFieldsInOneUpdate() throws Exception {
        UUID id = created("");
        send(patch(BASE + "/" + id), "{\"name\":\"Lahmacun\",\"basePrice\":20,\"ingredientsText\":\"Dough\",\"calories\":410,\"weightGrams\":180,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Lahmacun")).andExpect(jsonPath("$.ingredientsText").value("Dough"))
                .andExpect(jsonPath("$.calories").value(410)).andExpect(jsonPath("$.weightGrams").value(180)).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void anUpdateWithOnlyTheVersionIsStillA400ButNullNutritionCountsAsAField() throws Exception {
        UUID id = created("");
        send(patch(BASE + "/" + id), "{\"version\":0}").andExpect(status().isBadRequest());
        patchOf(id, "\"calories\":null", 0).andExpect(status().isOk());
    }

    @Test
    void aPartiallyInvalidUpdateAppliesNoneOfItsFields() throws Exception {
        UUID id = created("\"calories\":250,");
        var before = row(id);
        patchOf(id, "\"calories\":300,\"weightGrams\":0", 0).andExpect(status().isBadRequest());
        patchOf(id, "\"name\":\"Renamed\",\"estimatedPrepMinutes\":241", 0).andExpect(status().isBadRequest());
        patchOf(id, "\"calories\":300,\"currency\":\"ZZZ\"", 0).andExpect(status().isBadRequest());
        assertEquals(before, row(id));
    }

    @Test
    void otherContractsAreUnchanged() throws Exception {
        UUID id = created("\"ingredientsText\":\"Flour\",\"calories\":250,");
        patchOf(id, "\"ingredientsText\":\"\"", 0).andExpect(status().isOk()).andExpect(jsonPath("$.ingredientsText").doesNotExist())
                .andExpect(jsonPath("$.calories").value(250));
        send(patch(BASE + "/" + id), "{\"ingredientsText\":null,\"description\":\"Hot\",\"version\":1}").andExpect(status().isOk());
        send(patch(BASE + "/" + id), "{\"ingredientsText\":null,\"version\":2}").andExpect(status().isBadRequest());
    }

    // ---- optimistic locking ----

    @Test
    void aStaleVersionIs409EvenForANoOpOrAClear() throws Exception {
        UUID id = created("\"calories\":250,");
        patchOf(id, "\"calories\":260", 0).andExpect(status().isOk());
        patchOf(id, "\"calories\":260", 0).andExpect(status().isConflict());
        patchOf(id, "\"calories\":null", 0).andExpect(status().isConflict());
        patchOf(id, "\"weightGrams\":5", 7).andExpect(status().isConflict());
        assertEquals(260, row(id).get("calories"));
        assertEquals(1L, row(id).get("version"));
    }

    // ---- authorization, tenant isolation, mass assignment ----

    @Test
    void withoutAVerifiedActorTheAnswerIs401EvenForAnInvalidBody() throws Exception {
        UUID id = created("");
        actor.actAsNobody();
        send(post(BASE), create("\"calories\":250,")).andExpect(status().isUnauthorized());
        send(post(BASE), create("\"calories\":-5,")).andExpect(status().isUnauthorized());
        patchOf(id, "\"calories\":\"x\"", 0).andExpect(status().isUnauthorized());
        read(id).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).with(user("staff"))).andExpect(status().isUnauthorized());
        assertNull(row(id).get("calories"));
    }

    @Test
    void aNonAdminActorIs403EvenForAnInvalidBody() throws Exception {
        UUID id = created("");
        for (ActorRole role : ActorRole.values()) {
            if (role == ActorRole.ADMIN) {
                continue;
            }
            actor.actAs(RESTAURANT_A, role);
            send(post(BASE), create("\"calories\":250,")).andExpect(status().isForbidden());
            send(post(BASE), create("\"calories\":-5,")).andExpect(status().isForbidden());
            patchOf(id, "\"calories\":\"x\"", 0).andExpect(status().isForbidden());
            patchOf(id, "\"calories\":250", 0).andExpect(status().isForbidden());
            read(id).andExpect(status().isForbidden());
        }
        assertNull(row(id).get("calories"));
        assertEquals(0L, row(id).get("version"));
    }

    @Test
    void anotherRestaurantCannotReadOrChangeTheNutritionOfAProduct() throws Exception {
        UUID id = created("\"calories\":250,\"weightGrams\":12.5,\"estimatedPrepMinutes\":8,");
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        read(id).andExpect(status().isNotFound());
        patchOf(id, "\"calories\":1", 0).andExpect(status().isNotFound());
        patchOf(id, "\"calories\":null", 0).andExpect(status().isNotFound());
        mvc.perform(get(BASE).with(user("staff"))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        assertEquals(250, row(id).get("calories"));
        assertEquals(0L, row(id).get("version"));
    }

    @Test
    void aMissingProductIs404() throws Exception {
        patchOf(UUID.randomUUID(), "\"calories\":1", 0).andExpect(status().isNotFound());
    }

    @Test
    void aForgedRestaurantOrStateFlagIsStillRefusedAlongsideNutrition() throws Exception {
        send(post(BASE), create("\"calories\":250,\"restaurantId\":\"" + RESTAURANT_B + "\",")).andExpect(status().isBadRequest());
        send(post(BASE), create("\"calories\":250,\"isPublished\":true,")).andExpect(status().isBadRequest());
        send(post(BASE), create("\"calories\":250,\"isActive\":false,")).andExpect(status().isBadRequest());
        assertEquals(0, products());
        UUID id = created("");
        patchOf(id, "\"calories\":250,\"restaurantId\":\"" + RESTAURANT_B + "\"", 0).andExpect(status().isBadRequest());
        patchOf(id, "\"calories\":250,\"isPublished\":true", 0).andExpect(status().isBadRequest());
        patchOf(id, "\"calories\":250,\"isActive\":false", 0).andExpect(status().isBadRequest());
        assertNull(row(id).get("calories"));
        assertEquals(RESTAURANT_A, row(id).get("restaurant_id"));
    }

    @Test
    void nutritionDoesNotTouchPublicationAvailabilityOrPrice() throws Exception {
        UUID id = created("");
        var availabilityBefore = jdbc.queryForMap("SELECT status, version FROM product_availability WHERE product_id = ?", id);
        patchOf(id, "\"calories\":250,\"weightGrams\":12.5,\"estimatedPrepMinutes\":8", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublished").value(false)).andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.basePrice").value(12.5));
        assertEquals(availabilityBefore, jdbc.queryForMap("SELECT status, version FROM product_availability WHERE product_id = ?", id));
    }
}
