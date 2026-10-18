package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestPublicScopeConfiguration;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer menu against real PostgreSQL: which products and categories a customer sees, tenant isolation,
 * the category filters, the Turkish-aware name search, sorting and paging, and what the public JSON may contain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestPublicScopeConfiguration.class)
class PublicMenuApiTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID BETA = TestPublicScopeConfiguration.RESTAURANT_BETA;
    private static final String ALPHA_BASE = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF;
    private static final String BETA_BASE = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.BETA_REF;
    private static final Set<String> CARD_KEYS = Set.of("id", "name", "summary", "imageUrl", "basePrice", "currency", "priceIncludesVat",
            "category", "subcategory", "availability", "canOrder");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID food;
    private UUID soups;
    private UUID drinks;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        food = fixture.category(ALPHA, null, "Yemekler", 0, true);
        drinks = fixture.category(ALPHA, null, "İçecekler", 1, true);
        soups = fixture.category(ALPHA, food, "Çorbalar", 0, true);
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    private ResultActions products(String query) throws Exception {
        // A URI object, not a template: a template would encode the already percent-encoded query a second time.
        return mvc.perform(get(java.net.URI.create(ALPHA_BASE + "/products" + query)));
    }

    private List<String> names(String query) throws Exception {
        String body = products(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].name");
    }

    /** Percent-encoding for a query value; a space is %20 (MockMvc does not turn a plus sign back into a space). */
    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private UUID visible(UUID category, String name) {
        return fixture.visible(ALPHA, category, name);
    }

    // ---- who sees what ----

    @Test
    void onlyTheOpenAndOrderableStatesOfAProductDecideItsVisibility() throws Exception {
        fixture.product(ALPHA, food, "Shown", PublicMenuFixture.Spec.visible());
        fixture.product(ALPHA, food, "Archived", PublicMenuFixture.Spec.visible().archived());
        fixture.product(ALPHA, food, "Draft", PublicMenuFixture.Spec.visible().unpublished());
        fixture.product(ALPHA, food, "No availability record", PublicMenuFixture.Spec.visible().availability(null));
        UUID inactive = fixture.category(ALPHA, null, "Hidden category", 5, false);
        fixture.product(ALPHA, inactive, "In inactive category", PublicMenuFixture.Spec.visible());
        UUID inactiveParentSub = fixture.category(ALPHA, inactive, "Sub of inactive", 0, true);
        fixture.product(ALPHA, inactiveParentSub, "In sub of inactive", PublicMenuFixture.Spec.visible());
        UUID inactiveSub = fixture.category(ALPHA, food, "Inactive sub", 3, false);
        fixture.product(ALPHA, inactiveSub, "In inactive sub", PublicMenuFixture.Spec.visible());

        assertEquals(List.of("Shown"), names(""));
        products("").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void outOfStockAndTemporarilyClosedProductsAreShownButCannotBeOrdered() throws Exception {
        fixture.product(ALPHA, food, "A open", PublicMenuFixture.Spec.visible());
        fixture.product(ALPHA, food, "B out", PublicMenuFixture.Spec.visible().availability("OUT_OF_STOCK"));
        fixture.product(ALPHA, food, "C closed", PublicMenuFixture.Spec.visible().availability("TEMPORARILY_CLOSED"));

        String body = products("").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(List.of("A open", "B out", "C closed"), JsonPath.<List<String>>read(body, "$.items[*].name"));
        assertEquals(List.of("AVAILABLE", "OUT_OF_STOCK", "TEMPORARILY_CLOSED"), JsonPath.<List<String>>read(body, "$.items[*].availability"));
        assertEquals(List.of(true, false, false), JsonPath.<List<Boolean>>read(body, "$.items[*].canOrder"));
    }

    @Test
    void publishingNeverMakesAnArchivedOrHiddenProductVisible() throws Exception {
        fixture.product(ALPHA, food, "Published but archived", PublicMenuFixture.Spec.visible().archived());
        assertEquals(List.of(), names(""));
    }

    // ---- tenant isolation ----

    @Test
    void anotherRestaurantsMenuNeverAppearsAndItsDataCannotBeSearchedOrFiltered() throws Exception {
        UUID betaCategory = fixture.category(BETA, null, "Beta only", 0, true);
        UUID betaSub = fixture.category(BETA, betaCategory, "Beta sub", 0, true);
        fixture.visible(BETA, betaCategory, "Beta kebab");
        fixture.visible(BETA, betaSub, "Beta soup");
        visible(food, "Alpha pide");

        assertEquals(List.of("Alpha pide"), names(""));
        assertEquals(List.of(), names("?q=beta"));
        assertEquals(List.of("Beta kebab", "Beta soup"), JsonPath.<List<String>>read(
                mvc.perform(get(BETA_BASE + "/products")).andReturn().getResponse().getContentAsString(), "$.items[*].name"));

        products("?categoryId=" + betaCategory).andExpect(status().isNotFound());
        products("?subcategoryId=" + betaSub).andExpect(status().isNotFound());
        String categories = mvc.perform(get(ALPHA_BASE + "/categories")).andReturn().getResponse().getContentAsString();
        assertFalse(categories.contains("Beta"), categories);
    }

    @Test
    void theReferenceMustBeVerifiedAndTheRawRestaurantIdIsNotTrusted() throws Exception {
        visible(food, "Alpha pide");
        mvc.perform(get("/api/v1/menu/public/restaurants/" + ALPHA + "/products")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/menu/public/restaurants/unknown-ref/products")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/menu/public/restaurants/ALPHA-MENU/products")).andExpect(status().isNotFound());
    }

    @Test
    void identityHeadersAreIgnored() throws Exception {
        visible(food, "Alpha pide");
        fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        String body = mvc.perform(get(ALPHA_BASE + "/products").header("X-Restaurant-Id", BETA).header("X-Role", "ADMIN")
                .header("X-User-Id", UUID.randomUUID())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(List.of("Alpha pide"), JsonPath.<List<String>>read(body, "$.items[*].name"));
    }

    // ---- categories ----

    @Test
    void theCategoryTreeListsActiveCategoriesInOrderAndLeaksNothingElse() throws Exception {
        UUID inactiveRoot = fixture.category(ALPHA, null, "Inactive root", 2, false);
        fixture.category(ALPHA, inactiveRoot, "Child of inactive root", 0, true);
        fixture.category(ALPHA, food, "Inactive child", 9, false);
        fixture.category(ALPHA, food, "Salatalar", 1, true);

        String body = mvc.perform(get(ALPHA_BASE + "/categories")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertEquals(List.of("Yemekler", "İçecekler"), JsonPath.<List<String>>read(body, "$.categories[*].name"));
        assertEquals(List.of("Çorbalar", "Salatalar"), JsonPath.<List<String>>read(body, "$.categories[0].subcategories[*].name"));
        assertEquals(List.of(), JsonPath.<List<String>>read(body, "$.categories[1].subcategories"));
        assertEquals(Set.of("categories"), JsonPath.<Map<String, Object>>read(body, "$").keySet());
        assertEquals(Set.of("id", "name", "subcategories"), JsonPath.<Map<String, Object>>read(body, "$.categories[0]").keySet());
        assertEquals(Set.of("id", "name"), JsonPath.<Map<String, Object>>read(body, "$.categories[0].subcategories[0]").keySet());
        assertFalse(body.contains("Inactive"), body);
    }

    @Test
    void categoriesWithoutProductsAreListed() throws Exception {
        String body = mvc.perform(get(ALPHA_BASE + "/categories")).andReturn().getResponse().getContentAsString();
        assertEquals(2, JsonPath.<List<Object>>read(body, "$.categories").size());
    }

    // ---- category filters ----

    private void catalogue() {
        visible(food, "Direct food");
        visible(soups, "Lentil soup");
        UUID salads = fixture.category(ALPHA, food, "Salatalar", 1, true);
        visible(salads, "Garden salad");
        visible(drinks, "Tea");
    }

    @Test
    void aMainCategoryFilterCoversItsSubcategories() throws Exception {
        catalogue();
        assertEquals(List.of("Direct food", "Garden salad", "Lentil soup"), names("?categoryId=" + food));
        assertEquals(List.of("Tea"), names("?categoryId=" + drinks));
    }

    @Test
    void aSubcategoryFilterCoversOnlyThatSubcategory() throws Exception {
        catalogue();
        assertEquals(List.of("Lentil soup"), names("?subcategoryId=" + soups));
    }

    @Test
    void bothFiltersTogetherNeedAMatchingPair() throws Exception {
        catalogue();
        assertEquals(List.of("Lentil soup"), names("?categoryId=" + food + "&subcategoryId=" + soups));
        products("?categoryId=" + drinks + "&subcategoryId=" + soups).andExpect(status().isBadRequest());
    }

    @Test
    void theCategoryFiltersCombineWithSearchAndPaging() throws Exception {
        catalogue();
        assertEquals(List.of("Lentil soup"), names("?categoryId=" + food + "&q=lentil"));
        assertEquals(List.of(), names("?categoryId=" + drinks + "&q=lentil"));
        String body = products("?categoryId=" + food + "&size=2&page=1").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(List.of("Lentil soup"), JsonPath.<List<String>>read(body, "$.items[*].name"));
        assertEquals(3, ((Number) JsonPath.read(body, "$.totalElements")).intValue());
        assertEquals(2, ((Number) JsonPath.read(body, "$.totalPages")).intValue());
    }

    @Test
    void aMismatchedShapeOfFilterIsA400() throws Exception {
        products("?categoryId=" + soups).andExpect(status().isBadRequest());
        products("?subcategoryId=" + food).andExpect(status().isBadRequest());
        products("?categoryId=" + soups + "&subcategoryId=" + soups).andExpect(status().isBadRequest());
        products("?categoryId=" + food + "&subcategoryId=" + food).andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownForeignOrInactiveCategoryIsTheSame404() throws Exception {
        UUID inactiveRoot = fixture.category(ALPHA, null, "Inactive root", 4, false);
        UUID subOfInactive = fixture.category(ALPHA, inactiveRoot, "Sub of inactive", 0, true);
        UUID inactiveSub = fixture.category(ALPHA, food, "Inactive sub", 5, false);
        UUID foreign = fixture.root(BETA, "Foreign");
        List<String> bodies = new ArrayList<>();
        for (String query : new String[] {"?categoryId=" + UUID.randomUUID(), "?subcategoryId=" + UUID.randomUUID(), "?categoryId=" + foreign,
                "?categoryId=" + inactiveRoot, "?subcategoryId=" + subOfInactive, "?subcategoryId=" + inactiveSub}) {
            bodies.add(products(query).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        }
        for (String body : bodies) {
            assertEquals("The requested menu was not found.", JsonPath.read(body, "$.detail"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"?categoryId=abc", "?subcategoryId=1", "?categoryId=00000000-0000-0000-0000-00000000000g", "?categoryId=%27%20OR%201=1"})
    void aMalformedCategoryIdIsA400AndTheValueIsNotEchoed(String query) throws Exception {
        String body = products(query).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("OR 1"), body);
    }

    // ---- search ----

    @ParameterizedTest
    @CsvSource({"cay,Çay", "ÇAY,Çay", "çay,Çay", "Cay,Çay", "iskender,İskender", "ISKENDER,İskender", "İSKENDER,İskender", "ıskender,İskender",
            "isik,Işık çorbası", "ışık,Işık çorbası", "ISIK,Işık çorbası", "ordek,Ördek konfi", "ÖRDEK,Ördek konfi", "uzum,Üzümlü kek",
            "ÜZÜM,Üzümlü kek", "gozleme,Gözleme", "GÖZLEME,Gözleme", "sis,Şiş kebap", "ŞİŞ,Şiş kebap", "kofte,Izgara köfte", "KÖFTE,Izgara köfte",
            "izgara,Izgara köfte", "ızgara,Izgara köfte", "yagmur,Yağmur tatlısı", "YAĞMUR,Yağmur tatlısı"})
    void theSearchIgnoresCaseAndTurkishLetters(String query, String expected) throws Exception {
        for (String name : new String[] {"Çay", "İskender", "Işık çorbası", "Ördek konfi", "Üzümlü kek", "Gözleme", "Şiş kebap", "Izgara köfte",
                "Yağmur tatlısı", "Ayran"}) {
            visible(food, name);
        }
        assertEquals(List.of(expected), names("?q=" + encode(query)));
    }

    @Test
    void aSearchCoversTheNameButNotTheDescription() throws Exception {
        fixture.product(ALPHA, food, "Pide", PublicMenuFixture.Spec.visible().described("Contains kasar cheese"));
        assertEquals(List.of(), names("?q=kasar"));
        assertEquals(List.of("Pide"), names("?q=pid"));
    }

    @Test
    void aSearchFindsASubstringAnywhereInTheName() throws Exception {
        visible(food, "Adana kebap");
        visible(food, "Kebap tabağı");
        visible(food, "Pide");
        assertEquals(List.of("Adana kebap", "Kebap tabağı"), names("?q=kebap"));
        assertEquals(List.of("Adana kebap"), names("?q=dana%20keb"));
    }

    @Test
    void aDecomposedStoredNameStillMatches() throws Exception {
        visible(food, "Çay");
        visible(food, "Bă");
        assertEquals(List.of("Çay"), names("?q=cay"));
        assertEquals(List.of("Bă"), names("?q=ba"));
    }

    @Test
    void anAccentedNameMatchesWhateverTheCaseOfTheQuery() throws Exception {
        visible(food, "Éclair");
        visible(food, "Crème brûlée");
        assertEquals(List.of("Éclair"), names("?q=" + encode("ÉCLAIR")));
        assertEquals(List.of("Éclair"), names("?q=" + encode("éclair")));
        // Only the Turkish letters are folded to their base letter; other diacritics stay significant.
        assertEquals(List.of(), names("?q=ECLAIR"));
    }

    @Test
    void aBlankSearchIsTheNormalList() throws Exception {
        visible(food, "Pide");
        visible(food, "Çay");
        assertEquals(List.of("Çay", "Pide"), names("?q="));
        assertEquals(List.of("Çay", "Pide"), names("?q=%20%20%20"));
        assertEquals(List.of("Çay", "Pide"), names(""));
    }

    @Test
    void theSearchLengthIsTwoToFiftyAfterTrimming() throws Exception {
        visible(food, "Pide");
        products("?q=p").andExpect(status().isBadRequest());
        products("?q=%20p%20").andExpect(status().isBadRequest());
        products("?q=" + "a".repeat(51)).andExpect(status().isBadRequest());
        products("?q=pi").andExpect(status().isOk());
        products("?q=" + "a".repeat(50)).andExpect(status().isOk());
        products("?q=%20%20pide%20%20").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void innerWhitespaceIsCollapsed() throws Exception {
        visible(food, "Adana kebap");
        assertEquals(List.of("Adana kebap"), names("?q=adana%20%20%20%09kebap"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%00", "a%00b", "%01x", "a%1fb", "a%7fb"})
    void controlCharactersInTheSearchAreA400NotA500(String raw) throws Exception {
        products("?q=" + raw).andExpect(status().isBadRequest());
    }

    @Test
    void searchWildcardsAreLiteralText() throws Exception {
        visible(food, "100% Meyve");
        visible(food, "Under_score");
        visible(food, "Back\\slash");
        visible(food, "Pide");
        assertEquals(List.of("100% Meyve"), names("?q=100%25"));
        assertEquals(List.of("100% Meyve"), names("?q=%25%20M"));
        assertEquals(List.of("Under_score"), names("?q=r_s"));
        assertEquals(List.of(), names("?q=p_de"));
        assertEquals(List.of(), names("?q=%25%25"));
        assertEquals(List.of("Back\\slash"), names("?q=k%5Cs"));
        assertEquals(List.of(), names("?q=%5C%25"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"'; DROP TABLE products; --", "' OR '1'='1", "\" OR 1=1 --", "x') UNION SELECT * FROM categories --",
            "1; SELECT pg_sleep(5)", "$1", ":term", "${jndi:ldap://x}", "%'; DELETE FROM products; --"})
    void sqlInjectionAttemptsAreJustTextThatMatchesNothing(String attack) throws Exception {
        visible(food, "Pide");
        visible(food, "Çay");
        String encoded = encode(attack);
        products("?q=" + encoded).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class), "no statement was smuggled in");
    }

    @Test
    void anInjectionAttemptInTheNameThatIsStoredAsTextIsFoundAsText() throws Exception {
        visible(food, "x'; DROP TABLE products; --");
        assertEquals(List.of("x'; DROP TABLE products; --"), names("?q=" + encode("'; DROP")));
    }

    // ---- sorting and paging ----

    @Test
    void theDefaultOrderIsTheNameWithTurkishLettersAtTheirAlphabeticalPlace() throws Exception {
        for (String name : new String[] {"Zeytin", "Çay", "Ayran", "Şiş", "Dolma", "Sütlaç", "Ispanak", "Işık", "İskender", "Ördek", "Gözleme"}) {
            visible(food, name);
        }
        // Turkish letters are ordered as their base letter (Ç with C, Ş with S, İ and ı with I): a deliberate, collation-independent key.
        assertEquals(List.of("Ayran", "Çay", "Dolma", "Gözleme", "Işık", "İskender", "Ispanak", "Ördek", "Şiş", "Sütlaç", "Zeytin"), names(""));
        assertEquals(names(""), names("?sort=name"));
        assertEquals(names(""), names("?sort=name,asc"));
        List<String> reversed = new ArrayList<>(names(""));
        java.util.Collections.reverse(reversed);
        assertEquals(reversed, names("?sort=name,desc"));
    }

    @Test
    void anEmptyCategoryParameterIsNoFilter() throws Exception {
        visible(food, "Pide");
        visible(drinks, "Tea");
        assertEquals(List.of("Pide", "Tea"), names("?categoryId=&subcategoryId="));
    }

    @Test
    void thePriceAndCreationSortsWorkInBothDirections() throws Exception {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");
        fixture.product(ALPHA, food, "B mid", PublicMenuFixture.Spec.visible().priced("20").createdAt(base.plusSeconds(10)));
        fixture.product(ALPHA, food, "A cheap", PublicMenuFixture.Spec.visible().priced("5.5").createdAt(base.plusSeconds(30)));
        fixture.product(ALPHA, food, "C dear", PublicMenuFixture.Spec.visible().priced("99.99").createdAt(base.plusSeconds(20)));
        assertEquals(List.of("A cheap", "B mid", "C dear"), names("?sort=basePrice"));
        assertEquals(List.of("C dear", "B mid", "A cheap"), names("?sort=basePrice,desc"));
        assertEquals(List.of("B mid", "C dear", "A cheap"), names("?sort=createdAt,asc"));
        assertEquals(List.of("A cheap", "C dear", "B mid"), names("?sort=createdAt,desc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "restaurantId", "isActive", "version", "name,sideways", "name,asc,extra", "name;drop table products", "NAME",
            "basePrice desc", "p.name", "1", ",asc", "name,", "createdAt,ASC", "(select 1)"})
    void anySortOutsideTheAllowlistIsA400(String sort) throws Exception {
        visible(food, "Pide");
        String body = products("?sort=" + encode(sort))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("drop table"), body);
    }

    @ParameterizedTest
    @CsvSource({"page=-1", "size=0", "size=-5", "size=51", "size=9999", "page=abc", "size=abc", "page=99999999999", "size=1.5"})
    void invalidPagingIsA400(String query) throws Exception {
        products("?" + query).andExpect(status().isBadRequest());
    }

    @Test
    void thePagingBoundsAreAccepted() throws Exception {
        visible(food, "Pide");
        products("?size=1").andExpect(status().isOk());
        products("?size=50").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
        products("").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.page").value(0));
        products("?page=0").andExpect(status().isOk());
    }

    @Test
    void aPageBeyondTheEndIsEmptyButKeepsTheTotals() throws Exception {
        for (int i = 0; i < 5; i++) {
            visible(food, "Item " + i);
        }
        products("?size=2&page=9").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3));
        products("?size=5&page=1").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "basePrice", "createdAt", "name,desc", "basePrice,desc", "createdAt,desc"})
    void pagesNeverRepeatOrLoseAProductEvenWhenTheSortKeyTies(String sort) throws Exception {
        Instant same = Instant.parse("2026-02-02T08:00:00Z");
        Set<String> expected = new TreeSet<>();
        for (int i = 0; i < 25; i++) {
            expected.add(fixture.product(ALPHA, food, "Same name", PublicMenuFixture.Spec.visible().priced("7").createdAt(same)).toString());
        }
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            String body = products("?size=7&page=" + page + "&sort=" + sort).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.<List<String>>read(body, "$.items[*].id"));
            assertEquals(25, ((Number) JsonPath.read(body, "$.totalElements")).intValue());
        }
        assertEquals(25, seen.size());
        assertEquals(expected, new TreeSet<>(seen), "every product exactly once");
        assertEquals(new ArrayList<>(expected), seen, "ties are ordered by the primary key");
        List<String> again = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            again.addAll(JsonPath.<List<String>>read(
                    products("?size=7&page=" + page + "&sort=" + sort).andReturn().getResponse().getContentAsString(), "$.items[*].id"));
        }
        assertEquals(seen, again, "the same query gives the same order");
    }

    // ---- what the JSON contains ----

    @Test
    void aCardHasExactlyTheCustomerFieldsAndNoManagementData() throws Exception {
        UUID id = fixture.product(ALPHA, soups, "Mercimek", PublicMenuFixture.Spec.visible().described("Red lentil soup").priced("42.5").availability("OUT_OF_STOCK"));
        jdbc.update("UPDATE products SET image_url = 'https://img.example/a.png', ingredients_text = 'Lentil', calories = 120, "
                + "weight_grams = 250, estimated_prep_minutes = 5 WHERE id = ?", id);
        String body = products("").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> card = JsonPath.read(body, "$.items[0]");
        assertEquals(CARD_KEYS, card.keySet());
        assertEquals(id.toString(), card.get("id"));
        assertEquals("Red lentil soup", card.get("summary"));
        assertEquals("https://img.example/a.png", card.get("imageUrl"));
        assertEquals("TRY", card.get("currency"));
        assertEquals(true, card.get("priceIncludesVat"));
        assertEquals("OUT_OF_STOCK", card.get("availability"));
        assertEquals(false, card.get("canOrder"));
        assertEquals(0, new java.math.BigDecimal("42.5").compareTo(new java.math.BigDecimal(card.get("basePrice").toString())));
        assertEquals(Map.of("id", food.toString(), "name", "Yemekler"), card.get("category"));
        assertEquals(Map.of("id", soups.toString(), "name", "Çorbalar"), card.get("subcategory"));
        for (String forbidden : new String[] {"restaurantId", "version", "createdAt", "updatedAt", "createdBy", "updatedBy", "isActive", "isPublished",
                "reasonCode", "reasonText", "ingredientsText", "calories", "weightGrams", "estimatedPrepMinutes", "vatRate", "SOLD_OUT",
                ALPHA.toString(), "cccccccc-0000"}) {
            assertFalse(body.contains(forbidden), forbidden + " leaked: " + body);
        }
        assertEquals(Set.of("items", "page", "size", "totalElements", "totalPages"), JsonPath.<Map<String, Object>>read(body, "$").keySet());
    }

    @Test
    void aProductDirectlyInAMainCategoryHasNoSubcategory() throws Exception {
        visible(food, "Pide");
        products("").andExpect(jsonPath("$.items[0].subcategory").value((Object) null))
                .andExpect(jsonPath("$.items[0].category.name").value("Yemekler"));
    }

    @Test
    void theSummaryIsTheShortFormOfTheDescription() throws Exception {
        fixture.product(ALPHA, food, "A none", PublicMenuFixture.Spec.visible().priced("1"));
        fixture.product(ALPHA, food, "B short", PublicMenuFixture.Spec.visible().described("  Fresh\n\n  bread  ").priced("1"));
        fixture.product(ALPHA, food, "C long", PublicMenuFixture.Spec.visible().described(("word ".repeat(60)).strip()).priced("1"));
        String body = products("").andReturn().getResponse().getContentAsString();
        assertNull(JsonPath.read(body, "$.items[0].summary"));
        assertEquals("Fresh bread", JsonPath.read(body, "$.items[1].summary"));
        String summary = JsonPath.read(body, "$.items[2].summary");
        assertTrue(summary.codePointCount(0, summary.length()) <= 140, summary);
        assertTrue(summary.endsWith("word…"), summary);
    }

    // ---- changes show immediately ----

    @Test
    void anAvailabilityOrStateChangeShowsOnTheNextRead() throws Exception {
        UUID id = visible(food, "Pide");
        products("").andExpect(jsonPath("$.items[0].availability").value("AVAILABLE")).andExpect(jsonPath("$.items[0].canOrder").value(true));
        jdbc.update("UPDATE product_availability SET status = 'OUT_OF_STOCK', reason_code = 'SOLD_OUT' WHERE product_id = ?", id);
        products("").andExpect(jsonPath("$.items[0].availability").value("OUT_OF_STOCK")).andExpect(jsonPath("$.items[0].canOrder").value(false));
        jdbc.update("UPDATE products SET is_active = FALSE WHERE id = ?", id);
        products("").andExpect(jsonPath("$.totalElements").value(0));
        jdbc.update("UPDATE products SET is_active = TRUE WHERE id = ?", id);
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", food);
        products("").andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---- cost ----

    @Test
    void thePublicQueriesNeverGrowWithTheNumberOfProducts() throws Exception {
        for (int i = 0; i < 3; i++) {
            visible(i % 2 == 0 ? food : soups, "Few " + i);
        }
        long few = statementsFor("");
        for (int i = 0; i < 30; i++) {
            visible(i % 2 == 0 ? food : soups, "Many " + i);
        }
        assertEquals(few, statementsFor(""), "the statement count must not depend on the page size");
    }

    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private long statementsFor(String query) throws Exception {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        products(query).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }
}
