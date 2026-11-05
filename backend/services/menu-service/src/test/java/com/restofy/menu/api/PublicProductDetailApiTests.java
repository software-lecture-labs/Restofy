package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.application.service.PublicProductDetailService;
import com.restofy.menu.application.service.RatingSummary;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import com.restofy.menu.support.TestRatingSummaryPort;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer product detail on real PostgreSQL: visibility (one single 404 for everything hidden), the full set of
 * fields and nothing more, nutrition precision, active options and their order, tenant isolation, and the rating
 * summary with its fallbacks. The rating source is a test double; no real Rating service exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class PublicProductDetailApiTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID BETA = TestPublicScopeConfiguration.RESTAURANT_BETA;
    private static final String ALPHA_BASE = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF + "/products/";
    private static final Set<String> DETAIL_KEYS = Set.of("id", "name", "description", "ingredientsText", "imageUrl", "category", "subcategory",
            "basePrice", "currency", "priceIncludesVat", "calories", "weightGrams", "estimatedPrepMinutes", "availability", "canOrder",
            "includedIngredients", "extras", "rating");
    private static final String NOT_FOUND = "The requested menu was not found.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRatingSummaryPort rating;
    @Autowired PublicProductDetailService detailService;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private PublicMenuFixture fixture;
    private UUID main;
    private UUID sub;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        rating.reset();
        main = fixture.root(ALPHA, "Yemekler");
        sub = fixture.category(ALPHA, main, "Çorbalar", 0, true);
    }

    @AfterEach
    void clean() {
        rating.reset();
        fixture.clean();
    }

    private ResultActions detail(UUID id) throws Exception {
        return mvc.perform(get(ALPHA_BASE + id));
    }

    private String body(UUID id) throws Exception {
        return detail(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private UUID listed(String name) {
        return fixture.visible(ALPHA, main, name);
    }

    // ---- 200: fields ----

    @Test
    void aVisibleAvailableProductIsReturnedWithEveryDetailFieldAndNothingElse() throws Exception {
        UUID id = fixture.product(ALPHA, sub, "Mercimek", PublicMenuFixture.Spec.visible().described("Kırmızı mercimek,\nlimon ile servis edilir.").priced("42.5"));
        jdbc.update("UPDATE products SET image_url = 'https://img.example/a.png', ingredients_text = 'Mercimek, soğan, havuç', calories = 180, "
                + "weight_grams = 250.5, estimated_prep_minutes = 7 WHERE id = ?", id);

        String json = detail(id).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();

        Map<String, Object> body = JsonPath.read(json, "$");
        assertEquals(DETAIL_KEYS, body.keySet());
        assertEquals(id.toString(), body.get("id"));
        assertEquals("Mercimek", body.get("name"));
        assertEquals("Kırmızı mercimek,\nlimon ile servis edilir.", body.get("description"), "the full description, not a summary");
        assertEquals("Mercimek, soğan, havuç", body.get("ingredientsText"));
        assertEquals("https://img.example/a.png", body.get("imageUrl"));
        assertEquals(Map.of("id", main.toString(), "name", "Yemekler"), body.get("category"));
        assertEquals(Map.of("id", sub.toString(), "name", "Çorbalar"), body.get("subcategory"));
        assertEquals(0, new BigDecimal("42.5").compareTo(new BigDecimal(body.get("basePrice").toString())));
        assertEquals("TRY", body.get("currency"));
        assertEquals(true, body.get("priceIncludesVat"));
        assertEquals(180, body.get("calories"));
        assertEquals(250.5, ((Number) body.get("weightGrams")).doubleValue());
        assertEquals(7, body.get("estimatedPrepMinutes"));
        assertEquals("AVAILABLE", body.get("availability"));
        assertEquals(true, body.get("canOrder"));
        assertEquals(List.of(), body.get("includedIngredients"));
        assertEquals(List.of(), body.get("extras"));
    }

    @Test
    void aProductInAMainCategoryHasNoSubcategoryAndUnknownValuesStayNull() throws Exception {
        UUID id = listed("Pide");
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.subcategory").value((Object) null))
                .andExpect(jsonPath("$.category.name").value("Yemekler")).andExpect(jsonPath("$.description").value((Object) null))
                .andExpect(jsonPath("$.ingredientsText").value((Object) null)).andExpect(jsonPath("$.imageUrl").value((Object) null))
                .andExpect(jsonPath("$.calories").value((Object) null)).andExpect(jsonPath("$.weightGrams").value((Object) null))
                .andExpect(jsonPath("$.estimatedPrepMinutes").value((Object) null));
        Map<String, Object> keys = JsonPath.read(body(id), "$");
        assertEquals(DETAIL_KEYS, keys.keySet(), "unknown values are present as null, never missing and never 0");
    }

    @Test
    void zeroIsARealNutritionValueAndDifferentFromUnknown() throws Exception {
        UUID id = listed("Su");
        jdbc.update("UPDATE products SET calories = 0, estimated_prep_minutes = 0 WHERE id = ?", id);
        detail(id).andExpect(jsonPath("$.calories").value(0)).andExpect(jsonPath("$.estimatedPrepMinutes").value(0))
                .andExpect(jsonPath("$.weightGrams").value((Object) null));
    }

    @ParameterizedTest
    @CsvSource({"12.345,12.345", "12.5,12.5", "250,250", "0.001,0.001", "10000,10000", "100.100,100.1"})
    void theWeightKeepsItsPrecisionWithoutTrailingZerosOrExponent(String stored, String shown) throws Exception {
        UUID id = listed("Tabak");
        jdbc.update("UPDATE products SET weight_grams = CAST(? AS numeric) WHERE id = ?", stored, id);
        String json = body(id);
        assertTrue(json.contains("\"weightGrams\":" + shown + ","), json);
        assertFalse(json.contains("E+"), json);
    }

    @Test
    void thePriceIsTheStoredOneAndTheVatRateIsNeverShown() throws Exception {
        UUID id = fixture.product(ALPHA, main, "Pide", PublicMenuFixture.Spec.visible().priced("125.5"));
        String json = body(id);
        assertEquals(0, new BigDecimal("125.5").compareTo(new BigDecimal(JsonPath.read(json, "$.basePrice").toString())));
        assertFalse(json.contains("vatRate"), json);
        assertEquals(true, JsonPath.read(json, "$.priceIncludesVat"));
    }

    @Test
    void noManagementOrInternalDataIsInTheAnswer() throws Exception {
        UUID id = fixture.product(ALPHA, main, "Pide", PublicMenuFixture.Spec.visible().availability("OUT_OF_STOCK"));
        fixture.extra(ALPHA, id, "Peynir", "5", 2, 0, true);
        String json = body(id);
        for (String forbidden : new String[] {"restaurantId", "version", "createdAt", "updatedAt", "createdBy", "updatedBy", "isActive", "isPublished",
                "reasonCode", "reasonText", "SOLD_OUT", "commandId", "vatRate", "sortOrder", "minQuantity", ALPHA.toString(), "cccccccc-0000", "isVisibleToCustomer"}) {
            assertFalse(json.contains(forbidden), forbidden + " leaked: " + json);
        }
    }

    // ---- availability ----

    @ParameterizedTest
    @CsvSource({"AVAILABLE,true", "OUT_OF_STOCK,false", "TEMPORARILY_CLOSED,false"})
    void everyAvailabilityStateIsShownAndOnlyAvailableCanBeOrdered(String status, boolean canOrder) throws Exception {
        UUID id = fixture.product(ALPHA, main, "Pide", PublicMenuFixture.Spec.visible().availability(status));
        fixture.included(ALPHA, id, "Hamur", false, 0, true);
        fixture.extra(ALPHA, id, "Peynir", "5", 2, 0, true);
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.availability").value(status)).andExpect(jsonPath("$.canOrder").value(canOrder))
                .andExpect(jsonPath("$.includedIngredients.length()").value(1)).andExpect(jsonPath("$.extras.length()").value(1));
    }

    // ---- hidden: one single 404 ----

    private String notFoundBody(UUID id) throws Exception {
        String json = detail(id).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();
        assertEquals(NOT_FOUND, JsonPath.read(json, "$.detail"));
        return json;
    }

    @Test
    void everyHiddenUnknownOrForeignProductIsTheSame404AndTheRatingSourceIsNeverAsked() throws Exception {
        UUID archived = fixture.product(ALPHA, main, "Archived", PublicMenuFixture.Spec.visible().archived());
        UUID draft = fixture.product(ALPHA, main, "Draft", PublicMenuFixture.Spec.visible().unpublished());
        UUID noAvailability = fixture.product(ALPHA, main, "None", PublicMenuFixture.Spec.visible().availability(null));
        UUID inactiveCategory = fixture.product(ALPHA, fixture.category(ALPHA, null, "Off", 3, false), "In off", PublicMenuFixture.Spec.visible());
        UUID inactiveParent = fixture.product(ALPHA, fixture.category(ALPHA, fixture.category(ALPHA, null, "Off parent", 4, false), "Sub", 0, true),
                "In sub of off", PublicMenuFixture.Spec.visible());
        UUID inactiveSub = fixture.product(ALPHA, fixture.category(ALPHA, main, "Off sub", 5, false), "In off sub", PublicMenuFixture.Spec.visible());
        UUID foreign = fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        UUID unknown = UUID.randomUUID();

        List<String> bodies = new java.util.ArrayList<>();
        for (UUID id : new UUID[] {archived, draft, noAvailability, inactiveCategory, inactiveParent, inactiveSub, foreign, unknown}) {
            bodies.add(notFoundBody(id));
        }
        assertEquals(1, bodies.stream().map(b -> (String) JsonPath.read(b, "$.detail")).distinct().count());
        assertEquals(1, bodies.stream().map(b -> (String) JsonPath.read(b, "$.title")).distinct().count());
        assertEquals(List.of(), rating.calls(), "the rating source was not asked for any hidden product");
    }

    @Test
    void anUnverifiedRestaurantIsTheSame404AndNothingIsAsked() throws Exception {
        UUID id = listed("Pide");
        for (String ref : new String[] {"unknown-ref", ALPHA.toString(), "ALPHA-MENU"}) {
            mvc.perform(get("/api/v1/menu/public/restaurants/" + ref + "/products/" + id)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(NOT_FOUND));
        }
        assertEquals(List.of(), rating.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1", "00000000-0000-0000-0000-00000000000g", "%27%20OR%201=1", "not-a-uuid"})
    void aMalformedProductIdIs400AndTheValueIsNotEchoed(String raw) throws Exception {
        String json = mvc.perform(get(java.net.URI.create(ALPHA_BASE + raw))).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertEquals("A request parameter has an invalid value.", JsonPath.read(json, "$.detail"), "a fixed message, not the value");
        assertEquals(List.of(), rating.calls());
    }

    @Test
    void forgedIdentityHeadersGiveNothing() throws Exception {
        UUID foreign = fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        mvc.perform(get(ALPHA_BASE + foreign).header("X-Restaurant-Id", BETA).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        UUID own = listed("Pide");
        String json = mvc.perform(get(ALPHA_BASE + own).header("X-Restaurant-Id", BETA).header("X-Role", "ADMIN")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("Pide", JsonPath.read(json, "$.name"));
    }

    @Test
    void onlyGetIsAllowedOnTheDetailPath() throws Exception {
        UUID id = listed("Pide");
        mvc.perform(post(ALPHA_BASE + id)).andExpect(status().isForbidden());
    }

    @Test
    void aClosedProductThatIsAlsoHiddenIsStill404() throws Exception {
        UUID id = fixture.product(ALPHA, main, "Pide", PublicMenuFixture.Spec.visible().availability("OUT_OF_STOCK").archived());
        notFoundBody(id);
    }

    // ---- options ----

    @Test
    void includedIngredientsAndExtrasAreListedWithTheirOwnFieldsOnly() throws Exception {
        UUID id = listed("Pide");
        UUID dough = fixture.included(ALPHA, id, "Hamur", false, 0, true);
        UUID onion = fixture.included(ALPHA, id, "Soğan", true, 1, true);
        UUID cheese = fixture.extra(ALPHA, id, "Peynir", "7.5", 3, 0, true);
        UUID free = fixture.extra(ALPHA, id, "Sos", "0", 1, 1, true);

        String json = body(id);

        Map<String, Object> mandatory = JsonPath.read(json, "$.includedIngredients[0]");
        assertEquals(Set.of("id", "name", "removable"), mandatory.keySet());
        assertEquals(dough.toString(), mandatory.get("id"));
        assertEquals("Hamur", mandatory.get("name"));
        assertEquals(false, mandatory.get("removable"), "a mandatory ingredient cannot be removed");
        assertEquals(onion.toString(), JsonPath.read(json, "$.includedIngredients[1].id"));
        assertEquals(true, JsonPath.read(json, "$.includedIngredients[1].removable"));

        Map<String, Object> extra = JsonPath.read(json, "$.extras[0]");
        assertEquals(Set.of("id", "name", "priceDelta", "maxQuantity"), extra.keySet());
        assertEquals(cheese.toString(), extra.get("id"));
        assertEquals(0, new BigDecimal("7.5").compareTo(new BigDecimal(extra.get("priceDelta").toString())), "the price difference is carried as stored");
        assertEquals(3, extra.get("maxQuantity"));
        assertEquals(free.toString(), JsonPath.read(json, "$.extras[1].id"));
        assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(JsonPath.read(json, "$.extras[1].priceDelta").toString())), "a free extra is 0");
    }

    @Test
    void inactiveOptionsAreHiddenAndOtherProductsOrRestaurantsOptionsNeverAppear() throws Exception {
        UUID id = listed("Pide");
        UUID other = listed("Lahmacun");
        UUID betaProduct = fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        fixture.included(ALPHA, id, "Active included", true, 0, true);
        fixture.included(ALPHA, id, "Disabled included", true, 1, false);
        fixture.extra(ALPHA, id, "Active extra", "1", 1, 0, true);
        fixture.extra(ALPHA, id, "Disabled extra", "1", 1, 1, false);
        fixture.included(ALPHA, other, "Other product included", true, 0, true);
        fixture.extra(ALPHA, other, "Other product extra", "1", 1, 0, true);
        fixture.extra(BETA, betaProduct, "Beta extra", "1", 1, 0, true);

        String json = body(id);

        assertEquals(List.of("Active included"), JsonPath.<List<String>>read(json, "$.includedIngredients[*].name"));
        assertEquals(List.of("Active extra"), JsonPath.<List<String>>read(json, "$.extras[*].name"));
        assertFalse(json.contains("Disabled"), json);
        assertFalse(json.contains("Other product"), json);
        assertFalse(json.contains("Beta extra"), json);
    }

    @Test
    void optionsAreOrderedBySortOrderThenTurkishNameThenId() throws Exception {
        UUID id = listed("Pide");
        fixture.extra(ALPHA, id, "Zeytin", "1", 1, 0, true);
        fixture.extra(ALPHA, id, "Çilek", "1", 1, 0, true);
        fixture.extra(ALPHA, id, "Ananas", "1", 1, 0, true);
        fixture.extra(ALPHA, id, "Ilık süt", "1", 1, 0, true);
        fixture.extra(ALPHA, id, "İzmir", "1", 1, 0, true);
        fixture.extra(ALPHA, id, "Last by order", "1", 1, 5, true);
        fixture.extra(ALPHA, id, "First by order", "1", 1, 0, true);
        fixture.included(ALPHA, id, "Tuz", true, 2, true);
        fixture.included(ALPHA, id, "Biber", true, 2, true);
        fixture.included(ALPHA, id, "Un", true, 1, true);

        String json = body(id);

        assertEquals(List.of("Un", "Biber", "Tuz"), JsonPath.<List<String>>read(json, "$.includedIngredients[*].name"));
        List<String> extras = JsonPath.read(json, "$.extras[*].name");
        assertEquals("Last by order", extras.get(extras.size() - 1), "a higher sort order comes last");
        assertTrue(extras.indexOf("Ananas") < extras.indexOf("Çilek") && extras.indexOf("Çilek") < extras.indexOf("First by order")
                && extras.indexOf("İzmir") > extras.indexOf("Ilık süt") && extras.indexOf("Zeytin") > extras.indexOf("İzmir"), extras.toString());
        assertEquals(extras, JsonPath.<List<String>>read(body(id), "$.extras[*].name"), "the order is stable");
    }

    @Test
    void theSameNameMayBeAnIncludedIngredientAndAnExtraAndEachStaysInItsOwnList() throws Exception {
        UUID id = listed("Pide");
        fixture.included(ALPHA, id, "Peynir", true, 0, true);
        fixture.extra(ALPHA, id, "Peynir", "5", 2, 0, true);
        String json = body(id);
        assertEquals(List.of("Peynir"), JsonPath.<List<String>>read(json, "$.includedIngredients[*].name"));
        assertEquals(List.of("Peynir"), JsonPath.<List<String>>read(json, "$.extras[*].name"));
    }

    // ---- statements ----

    private long statementsFor(UUID id) throws Exception {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        detail(id).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    @Test
    void theNumberOfStatementsDoesNotGrowWithTheNumberOfOptions() throws Exception {
        UUID few = listed("Few");
        fixture.included(ALPHA, few, "One", true, 0, true);
        long withOne = statementsFor(few);
        UUID many = listed("Many");
        for (int i = 0; i < 25; i++) {
            fixture.included(ALPHA, many, "Included " + i, true, i, true);
            fixture.extra(ALPHA, many, "Extra " + i, "1", 1, i, true);
        }
        assertEquals(withOne, statementsFor(many), "options are read with one statement whatever their number");
    }

    // ---- rating ----

    @Test
    void aRealRatingSummaryIsReturnedAsGiven() throws Exception {
        UUID id = listed("Pide");
        rating.answer(RatingSummary.rated(new BigDecimal("4.5"), 12));
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.rating.status").value("RATED"))
                .andExpect(jsonPath("$.rating.averageRating").value(4.5)).andExpect(jsonPath("$.rating.ratingCount").value(12));
    }

    @Test
    void noRatingsIsNotRatedWithCountZeroAndNoAverage() throws Exception {
        UUID id = listed("Pide");
        rating.answer(RatingSummary.notRated());
        detail(id).andExpect(jsonPath("$.rating.status").value("NOT_RATED")).andExpect(jsonPath("$.rating.averageRating").value((Object) null))
                .andExpect(jsonPath("$.rating.ratingCount").value(0));
    }

    @Test
    void anUnavailableSourceIsUnavailableWithNoNumbersNotZero() throws Exception {
        UUID id = listed("Pide");
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.rating.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.rating.averageRating").value((Object) null)).andExpect(jsonPath("$.rating.ratingCount").value((Object) null));
        Map<String, Object> ratingJson = JsonPath.read(body(id), "$.rating");
        assertEquals(Set.of("status", "averageRating", "ratingCount"), ratingJson.keySet());
    }

    @Test
    void aFailingRatingSourceNeverBreaksTheDetail() throws Exception {
        UUID id = fixture.product(ALPHA, main, "Pide", PublicMenuFixture.Spec.visible().described("Full text"));
        fixture.extra(ALPHA, id, "Peynir", "5", 2, 0, true);
        for (RuntimeException failure : new RuntimeException[] {new IllegalStateException("secret-internal-host:8080 down"),
                new org.springframework.web.client.ResourceAccessException("timed out"), new NullPointerException(), new UnsupportedOperationException()}) {
            rating.failWith(failure);
            String json = detail(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals("UNAVAILABLE", JsonPath.read(json, "$.rating.status"));
            assertNull(JsonPath.read(json, "$.rating.averageRating"));
            assertNull(JsonPath.read(json, "$.rating.ratingCount"));
            assertEquals("Full text", JsonPath.read(json, "$.description"), "the product is still complete");
            assertEquals(1, JsonPath.<List<Object>>read(json, "$.extras").size());
            assertFalse(json.contains("secret-internal-host"), "the failure text never reaches the customer");
        }
    }

    @Test
    void aNullAnswerFromTheRatingSourceIsUnavailable() throws Exception {
        UUID id = listed("Pide");
        rating.answerWith(call -> null);
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.rating.status").value("UNAVAILABLE"));
    }

    @ParameterizedTest
    @CsvSource({"RATED,4.5,-3", "RATED,4.5,0", "RATED,0,5", "RATED,0.99,5", "RATED,5.01,5", "RATED,6,5", "RATED,-1,5", "RATED,,5", "RATED,4.5,",
            "NOT_RATED,,5", "NOT_RATED,4,0", "NOT_RATED,,", "NOT_RATED,,-1", "UNAVAILABLE,4,3"})
    void aSelfContradictingOrInvalidRatingAnswerIsNeverShown(String status, String average, Long count) throws Exception {
        UUID id = listed("Pide");
        rating.answer(new RatingSummary(RatingSummary.Status.valueOf(status), average == null ? null : new BigDecimal(average), count));
        detail(id).andExpect(status().isOk()).andExpect(jsonPath("$.rating.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.rating.averageRating").value((Object) null)).andExpect(jsonPath("$.rating.ratingCount").value((Object) null));
    }

    @Test
    void theRatingSourceIsAskedOnceWithTheVerifiedRestaurantAndProductAfterTheTransactionEnded() throws Exception {
        UUID id = listed("Pide");
        body(id);
        List<TestRatingSummaryPort.Call> calls = rating.calls();
        assertEquals(1, calls.size());
        assertEquals(ALPHA, calls.get(0).restaurantId(), "the restaurant the resolver verified");
        assertEquals(id, calls.get(0).productId());
        assertFalse(calls.get(0).inTransaction(), "no database transaction is open while the rating source is asked");
        assertFalse(PublicProductDetailService.class.isAnnotationPresent(Transactional.class), "the orchestration itself is not transactional");
    }

    @Test
    void anotherRestaurantsRatingIsNeverUsed() throws Exception {
        UUID alphaProduct = listed("Pide");
        UUID betaProduct = fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        rating.answerWith(call -> BETA.equals(call.restaurantId()) ? RatingSummary.rated(new BigDecimal("5"), 99) : RatingSummary.notRated());

        detail(alphaProduct).andExpect(jsonPath("$.rating.status").value("NOT_RATED"));
        detail(betaProduct).andExpect(status().isNotFound());

        assertEquals(1, rating.calls().size(), "the foreign product never reached the rating source");
        assertEquals(ALPHA, rating.calls().get(0).restaurantId());
    }

    @Test
    void aClosedButVisibleProductStillGetsItsRating() throws Exception {
        UUID id = fixture.product(ALPHA, main, "Pide", PublicMenuFixture.Spec.visible().availability("TEMPORARILY_CLOSED"));
        rating.answer(RatingSummary.rated(new BigDecimal("3"), 2));
        detail(id).andExpect(jsonPath("$.canOrder").value(false)).andExpect(jsonPath("$.rating.status").value("RATED"));
    }

    @Test
    void theDetailServiceReturnsTheSameSnapshotItsPartsCameFrom() {
        UUID id = listed("Pide");
        fixture.included(ALPHA, id, "Hamur", true, 0, true);
        var result = detailService.detail(TestPublicScopeConfiguration.ALPHA_REF, id);
        assertEquals(id, result.listed().view().product().getId());
        assertEquals(1, result.listed().includedIngredients().size());
        assertEquals(RatingSummary.unavailable(), result.rating());
    }
}
