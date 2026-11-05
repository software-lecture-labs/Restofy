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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Name, description, price, currency, VAT and VAT-inclusion limits at the HTTP boundary, for create AND
 * partial update, against real PostgreSQL. A rejected request must never write or change anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductFieldValidationApiTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String BASE = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID category;

    @BeforeEach
    void data() {
        category = categories.saveAndFlush(Category.root(RESTAURANT, "Main", 0)).getId();
        actor.actAs(RESTAURANT, ActorRole.ADMIN);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
        actor.actAsNobody();
    }

    // ---- helpers ----

    /** A valid create body with one field replaced by a raw JSON value (or removed when {@code raw == null}). */
    private String create(String field, String raw) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("categoryId", "\"" + category + "\"");
        fields.put("name", "\"Tea\"");
        fields.put("basePrice", "5");
        fields.put("currency", "\"TRY\"");
        fields.put("vatRate", "10");
        fields.put("priceIncludesVat", "true");
        if (field != null) {
            if (raw == null) fields.remove(field); else fields.put(field, raw);
        }
        var body = new StringBuilder("{");
        fields.forEach((k, v) -> body.append(body.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return body.append('}').toString();
    }

    /** A JSON string literal. Control characters and surrogates are sent as \\uXXXX escapes, exactly as a real client would. */
    private static String json(String text) {
        var out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 || Character.isSurrogate(c) ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions submit(String body) throws Exception {
        return send(post(BASE), body);
    }

    private UUID seeded() throws Exception {
        String body = submit(create(null, null)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions patchOf(UUID id, String field, String raw) throws Exception {
        return send(patch(BASE + "/" + id), "{\"" + field + "\":" + raw + ",\"version\":0}");
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT name, description, base_price, currency, vat_rate, price_includes_vat, version, updated_by_user_id "
                + "FROM products WHERE id = ?", id);
    }

    private int products() {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }

    private void rejectedOnCreateAndUpdate(String field, String raw) throws Exception {
        submit(create(field, raw)).andExpect(status().isBadRequest());
        assertEquals(0, products(), field + "=" + raw + " must not create a product");
        UUID id = seeded();
        var before = row(id);
        patchOf(id, field, raw).andExpect(status().isBadRequest());
        assertEquals(before, row(id), field + "=" + raw + " must not change the product");
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
    }

    // ---- name ----

    @Test
    void requiredNameRulesHoldOnCreateAndUpdate() throws Exception {
        submit(create("name", null)).andExpect(status().isBadRequest());
        submit(create("name", "null")).andExpect(status().isBadRequest());
        for (String bad : new String[] {"\"\"", "\"   \"", json("\t\n "), json("\u00A0\u2003")}) {
            submit(create("name", bad)).andExpect(status().isBadRequest());
        }
        assertEquals(0, products());
        UUID id = seeded();
        for (String bad : new String[] {"\"\"", "\"   \"", json("\u00A0")}) {
            patchOf(id, "name", bad).andExpect(status().isBadRequest());
        }
        assertEquals("Tea", row(id).get("name"));
    }

    @Test
    void nameIsTrimmedAndWhitespaceIsCollapsedIncludingUnicodeSpaces() throws Exception {
        submit(create("name", json("  Lamb\u00A0\u2003  kebab\t\n"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Lamb kebab"));
    }

    @Test
    void unicodeNamesAreKeptIntact() throws Exception {
        for (String name : new String[] {"Çiğ köfte İşkembe", "Şiş Tavuk ğüöı", "寿司", "Crème brûlée 🍰", "Кофе"}) {
            submit(create("name", json(name))).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(name));
        }
        assertEquals("Çiğ köfte İşkembe", jdbc.queryForObject("SELECT name FROM products WHERE name LIKE 'Çiğ%'", String.class));
    }

    @Test
    void nameBoundaryIs200Characters() throws Exception {
        submit(create("name", json("n".repeat(200)))).andExpect(status().isCreated());
        submit(create("name", json("n".repeat(201)))).andExpect(status().isBadRequest());
        UUID id = seeded();
        patchOf(id, "name", json("m".repeat(200))).andExpect(status().isOk());
        patchOf(id, "name", json("m".repeat(201))).andExpect(status().isBadRequest());
        assertEquals(1L, ((Number) row(id).get("version")).longValue(), "only the one valid update changed the product");
    }

    @Test
    void theLimitAppliesToTheSubmittedTextSoPaddingCountsTowardsIt() throws Exception {
        // Documented behaviour: the DTO bound is checked before trimming, a client must send trimmed text.
        submit(create("name", json("n".repeat(200) + " "))).andExpect(status().isBadRequest());
        submit(create("name", json(" " + "n".repeat(198) + " "))).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\u0001b", "a\u001fb", "a\u007fb", "a\u0085b", "a\u009fb", "ab\ud800", "\udc00ab", "a\ud800b"})
    void controlCharactersAndBrokenSurrogatesInTheNameAreA400NotA500(String name) throws Exception {
        submit(create("name", json(name))).andExpect(status().isBadRequest());
        assertEquals(0, products());
        UUID id = seeded();
        patchOf(id, "name", json(name)).andExpect(status().isBadRequest());
        assertEquals("Tea", row(id).get("name"));
    }

    // ---- description ----

    @Test
    void descriptionIsOptionalAndBlankBecomesNull() throws Exception {
        submit(create("description", null)).andExpect(status().isCreated()).andExpect(jsonPath("$.description").doesNotExist());
        submit(create("description", "null")).andExpect(status().isCreated()).andExpect(jsonPath("$.description").doesNotExist());
        submit(create("description", "\"   \"")).andExpect(status().isCreated()).andExpect(jsonPath("$.description").doesNotExist());
        submit(create("description", json("  grilled \n over charcoal  "))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("grilled \n over charcoal"));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM products WHERE description IS NULL", Integer.class));
    }

    @Test
    void descriptionBoundaryIs2000Characters() throws Exception {
        submit(create("description", json("d".repeat(2000)))).andExpect(status().isCreated());
        submit(create("description", json("d".repeat(2001)))).andExpect(status().isBadRequest());
        UUID id = seeded();
        patchOf(id, "description", json("d".repeat(2001))).andExpect(status().isBadRequest());
        assertNull(row(id).get("description"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\u0007b", "a\u001bb", "ab\ud800"})
    void controlCharactersInTheDescriptionAreA400NotA500(String text) throws Exception {
        submit(create("description", json(text))).andExpect(status().isBadRequest());
        UUID id = seeded();
        patchOf(id, "description", json(text)).andExpect(status().isBadRequest());
        assertNull(row(id).get("description"));
    }

    @Test
    void updateCanClearTheDescriptionWithABlankText() throws Exception {
        UUID id = seeded();
        patchOf(id, "description", json("hot")).andExpect(status().isOk());
        send(patch(BASE + "/" + id), "{\"description\":\"  \",\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());
        assertNull(row(id).get("description"));
    }

    // ---- price ----

    @Test
    void zeroPriceIsValidOnPostAndOnUpdate() throws Exception {
        submit(create("basePrice", "0")).andExpect(status().isCreated()).andExpect(jsonPath("$.basePrice").value(0));
        submit(create("basePrice", "0.0000")).andExpect(status().isCreated());
        submit(create("basePrice", "-0")).andExpect(status().isCreated()).andExpect(jsonPath("$.basePrice").value(0));
        submit(create("basePrice", "0e5")).andExpect(status().isCreated());
        UUID id = seeded();
        patchOf(id, "basePrice", "0").andExpect(status().isOk()).andExpect(jsonPath("$.basePrice").value(0));
        assertEquals(0, ((java.math.BigDecimal) row(id).get("base_price")).signum());
        send(patch(BASE + "/" + id), "{\"basePrice\":12.5,\"version\":1}").andExpect(status().isOk());
        send(patch(BASE + "/" + id), "{\"basePrice\":0.00,\"version\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(0));
    }

    @Test
    void positivePricesAreKeptExactlyWithoutFloatingPointNoise() throws Exception {
        submit(create("basePrice", "0.1")).andExpect(status().isCreated()).andExpect(jsonPath("$.basePrice").value(0.1));
        submit(create("basePrice", "19.99")).andExpect(status().isCreated());
        submit(create("basePrice", "0.0001")).andExpect(status().isCreated());
        submit(create("basePrice", "123456789012345.6789")).andExpect(status().isCreated());
        assertEquals(new java.math.BigDecimal("0.1000"), jdbc.queryForObject("SELECT base_price FROM products WHERE base_price < 0.2 AND base_price > 0.0999", java.math.BigDecimal.class));
        assertEquals(new java.math.BigDecimal("123456789012345.6789"), jdbc.queryForObject("SELECT max(base_price) FROM products", java.math.BigDecimal.class));
    }

    @Test
    void priceUpperBoundIsTheDatabaseBoundAndOneStepAboveIsRejected() throws Exception {
        submit(create("basePrice", "999999999999999.9999")).andExpect(status().isCreated());
        submit(create("basePrice", "1000000000000000")).andExpect(status().isBadRequest());
        submit(create("basePrice", "1000000000000000.0")).andExpect(status().isBadRequest());
        submit(create("basePrice", "9999999999999999.9999")).andExpect(status().isBadRequest());
        UUID id = seeded();
        patchOf(id, "basePrice", "999999999999999.9999").andExpect(status().isOk());
        send(patch(BASE + "/" + id), "{\"basePrice\":1000000000000000,\"version\":1}").andExpect(status().isBadRequest());
    }

    @Test
    void invalidPricesAreRejectedOnCreateAndUpdateWithoutChangingAnything() throws Exception {
        for (String raw : new String[] {"-1", "-0.0001", "-0.01", "-999999999999999.9999", "1.00001", "0.00001", "5.123456789",
                "\"abc\"", "\"\"", "true", "[1]", "{}", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "1e999999999", "1E+400", "-1e999999999"}) {
            rejectedOnCreateAndUpdate("basePrice", raw);
        }
    }

    @Test
    void aMissingOrNullPriceIsRejectedOnCreateButAnUpdateMayOmitIt() throws Exception {
        submit(create("basePrice", null)).andExpect(status().isBadRequest());
        submit(create("basePrice", "null")).andExpect(status().isBadRequest());
        UUID id = seeded();
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(5));
    }

    @Test
    void bareNonNumbersAndBrokenJsonAreRejected() throws Exception {
        submit("{\"categoryId\":\"" + category + "\",\"name\":\"Tea\",\"basePrice\":NaN,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}")
                .andExpect(status().isBadRequest());
        submit("{\"categoryId\":\"" + category + "\",\"name\":\"Tea\",\"basePrice\":Infinity,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}")
                .andExpect(status().isBadRequest());
        submit("{not json").andExpect(status().isBadRequest());
        submit("").andExpect(status().isBadRequest());
        submit("[]").andExpect(status().isBadRequest());
        submit("null").andExpect(status().isBadRequest());
        assertEquals(0, products());
    }

    // ---- VAT ----

    @Test
    void vatBoundariesZeroAndOneHundredAreValidAndFractionsInBetweenToo() throws Exception {
        for (String rate : new String[] {"0", "0.0001", "8", "8.5", "18", "99.9999", "100", "100.0000", "1e1"}) {
            submit(create("vatRate", rate)).andExpect(status().isCreated());
        }
        UUID id = seeded();
        patchOf(id, "vatRate", "0").andExpect(status().isOk());
        send(patch(BASE + "/" + id), "{\"vatRate\":100,\"version\":1}").andExpect(status().isOk());
    }

    @Test
    void invalidVatRatesAreRejectedOnCreateAndUpdateWithoutChangingAnything() throws Exception {
        for (String raw : new String[] {"-0.0001", "-1", "100.0001", "100.5", "101", "1000", "0.00001", "99.99999", "\"abc\"", "\"NaN\"",
                "false", "[]", "1e999999999"}) {
            rejectedOnCreateAndUpdate("vatRate", raw);
        }
    }

    @Test
    void aMissingVatRateIsRejectedOnCreate() throws Exception {
        submit(create("vatRate", null)).andExpect(status().isBadRequest());
        submit(create("vatRate", "null")).andExpect(status().isBadRequest());
    }

    // ---- price includes VAT ----

    @Test
    void vatInclusionMustBeStatedAndIsStoredAsGiven() throws Exception {
        submit(create("priceIncludesVat", null)).andExpect(status().isBadRequest());
        submit(create("priceIncludesVat", "null")).andExpect(status().isBadRequest());
        submit(create("priceIncludesVat", "false")).andExpect(status().isCreated()).andExpect(jsonPath("$.priceIncludesVat").value(false));
        submit(create("priceIncludesVat", "true")).andExpect(status().isCreated()).andExpect(jsonPath("$.priceIncludesVat").value(true));
        assertEquals(2, products());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"maybe\"", "\"yes\"", "\"\"", "0", "1", "2", "-1", "1.5", "[]", "{}"})
    void nonBooleanVatInclusionIsRejected(String raw) throws Exception {
        submit(create("priceIncludesVat", raw)).andExpect(status().isBadRequest());
        assertEquals(0, products());
        UUID id = seeded();
        patchOf(id, "priceIncludesVat", raw).andExpect(status().isBadRequest());
        assertEquals(Boolean.TRUE, row(id).get("price_includes_vat"));
    }

    @Test
    void changingTheVatInclusionNeverRewritesThePrice() throws Exception {
        UUID id = seeded();
        patchOf(id, "priceIncludesVat", "false").andExpect(status().isOk())
                .andExpect(jsonPath("$.priceIncludesVat").value(false)).andExpect(jsonPath("$.basePrice").value(5))
                .andExpect(jsonPath("$.vatRate").value(10));
        assertEquals(0, new java.math.BigDecimal("5").compareTo((java.math.BigDecimal) row(id).get("base_price")));
    }

    // ---- currency ----

    @ParameterizedTest
    @ValueSource(strings = {"TRY", "USD", "EUR", "GBP", "JPY", "CHF", "SAR", "AED"})
    void realIsoCurrencyCodesAreAccepted(String code) throws Exception {
        submit(create("currency", json(code))).andExpect(status().isCreated()).andExpect(jsonPath("$.currency").value(code));
        assertEquals(code, jdbc.queryForObject("SELECT currency FROM products", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XAF", "XOF", "XCD", "XPF"})
    void genuineCurrenciesThatStartWithXAreAccepted(String code) throws Exception {
        submit(create("currency", json(code))).andExpect(status().isCreated()).andExpect(jsonPath("$.currency").value(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XXX", "XTS", "XAU", "XAG", "XPD", "XPT", "XDR", "XBA", "XSU", "XUA", "XAD", "BOV", "CHE", "CHW", "CLF", "COU", "MXV", "USN", "UYI"})
    void specialPurposeCurrencyCodesCannotBeUsedForPricesOnCreateAndUpdate(String code) throws Exception {
        String body = submit(create("currency", json(code))).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertTrue(JsonPath.<String>read(body, "$.detail").startsWith("Currency "), body);
        assertEquals(0, products());
        UUID id = seeded();
        var before = row(id);
        patchOf(id, "currency", json(code)).andExpect(status().isBadRequest());
        assertEquals(before, row(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZZ", "ABC", "QQQ", "AAA", "try", "Try", "usd", "TR", "T", "TRYY", "TR1", "T R", " TRY", "TRY ", "\u00dcSD", "\"\"", ""})
    void unknownMalformedOrWronglyCasedCurrenciesAreRejectedOnCreateAndUpdate(String code) throws Exception {
        rejectedOnCreateAndUpdate("currency", json(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "123", "true", "[\"TRY\"]", "{}"})
    void nonStringCurrenciesAreRejected(String raw) throws Exception {
        post400(create("currency", raw));
        UUID id = seeded();
        var before = row(id);
        patchOf(id, "currency", raw.equals("null") ? "123" : raw).andExpect(status().isBadRequest());
        assertEquals(before, row(id));
    }

    @Test
    void aMissingCurrencyIsRejectedOnCreateButAnUpdateMayOmitIt() throws Exception {
        post400(create("currency", null));
        UUID id = seeded();
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("TRY"));
    }

    @Test
    void anIsoButUnknownCodeFailsAsAValidationErrorWithAFixedMessage() throws Exception {
        String body = submit(create("currency", "\"ZZZ\"")).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertEquals("Currency must be a valid ISO 4217 currency code.", JsonPath.read(body, "$.detail"));
        assertFalse(body.contains("ZZZ"), "the submitted value is not echoed back");
    }

    @Test
    void updateChangesTheCurrencyWithoutTouchingThePriceAmount() throws Exception {
        UUID id = seeded();
        patchOf(id, "currency", "\"EUR\"").andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.basePrice").value(5));
    }

    // ---- partial update atomicity ----

    @Test
    void aPartiallyValidUpdateChangesNothing() throws Exception {
        UUID id = seeded();
        var before = row(id);
        // The name is valid but the currency passes the pattern and fails the ISO check inside the domain.
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"basePrice\":9,\"currency\":\"ZZZ\",\"version\":0}")
                .andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"basePrice\":9,\"vatRate\":101,\"version\":0}")
                .andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\\u0000\",\"basePrice\":9,\"version\":0}")
                .andExpect(status().isBadRequest());
        assertEquals(before, row(id), "name, price, audit actor and version are all untouched");
    }

    private void post400(String body) throws Exception {
        submit(body).andExpect(status().isBadRequest());
        assertEquals(0, products());
    }
}
