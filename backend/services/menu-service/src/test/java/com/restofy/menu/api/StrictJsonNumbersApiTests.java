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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Price, VAT and version must be real JSON numbers; valid numeric behaviour is unchanged. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class StrictJsonNumbersApiTests {

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

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String create(String price, String vat) {
        return "{\"categoryId\":\"" + category + "\",\"name\":\"Tea\",\"basePrice\":" + price + ",\"currency\":\"TRY\","
                + "\"vatRate\":" + vat + ",\"priceIncludesVat\":true}";
    }

    private UUID seeded() throws Exception {
        String body = send(post(BASE), create("5", "10")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT base_price, vat_rate, is_active, is_published, version FROM products WHERE id = ?", id);
    }

    private int products() {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }

    // ---- price and VAT ----

    @ParameterizedTest
    @ValueSource(strings = {"\"12.5\"", "\"0\"", "\" 5 \"", "\"1e3\"", "\"\"", "\"abc\"", "\"5,5\"", "\"+5\"", "\"٥\""})
    void quotedPricesAreRejectedOnCreateAndUpdate(String price) throws Exception {
        send(post(BASE), create(price, "10")).andExpect(status().isBadRequest());
        assertEquals(0, products());
        UUID id = seeded();
        var before = row(id);
        send(patch(BASE + "/" + id), "{\"basePrice\":" + price + ",\"version\":0}").andExpect(status().isBadRequest());
        assertEquals(before, row(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"8\"", "\"0\"", "\"100\"", "\" 10 \"", "\"1e1\"", "\"\"", "\"abc\"", "\"10.5\""})
    void quotedVatRatesAreRejectedOnCreateAndUpdate(String vat) throws Exception {
        send(post(BASE), create("5", vat)).andExpect(status().isBadRequest());
        assertEquals(0, products());
        UUID id = seeded();
        var before = row(id);
        send(patch(BASE + "/" + id), "{\"vatRate\":" + vat + ",\"version\":0}").andExpect(status().isBadRequest());
        assertEquals(before, row(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "[5]", "{\"v\":5}"})
    void otherTokenTypesAreRejectedToo(String raw) throws Exception {
        send(post(BASE), create(raw, "10")).andExpect(status().isBadRequest());
        send(post(BASE), create("5", raw)).andExpect(status().isBadRequest());
        assertEquals(0, products());
    }

    @Test
    void realJsonNumbersKeepWorkingExactlyAsBefore() throws Exception {
        for (String[] pair : new String[][] {{"12.5", "8.5"}, {"0", "0"}, {"-0", "-0"}, {"0e5", "1e1"}, {"12.50", "100"},
                {"0.1", "99.9999"}, {"123456789012345.6789", "0.0001"}, {"1E+3", "1E+2"}, {"5.0", "10.00"}}) {
            send(post(BASE), create(pair[0], pair[1])).andExpect(status().isCreated());
        }
        assertEquals(9, products());
        assertEquals(new BigDecimal("0.1000"), jdbc.queryForObject("SELECT base_price FROM products WHERE base_price BETWEEN 0.09 AND 0.11", BigDecimal.class),
                "0.1 is stored exactly, with no floating-point noise");
        assertEquals(new BigDecimal("123456789012345.6789"), jdbc.queryForObject("SELECT max(base_price) FROM products WHERE base_price < 1E+15", BigDecimal.class));
        assertEquals(new BigDecimal("1000.0000"), jdbc.queryForObject("SELECT base_price FROM products WHERE base_price = 1000", BigDecimal.class));
    }

    @Test
    void validNumbersStillWorkOnUpdateAndNullStillMeansUnchanged() throws Exception {
        UUID id = seeded();
        send(patch(BASE + "/" + id), "{\"basePrice\":0,\"vatRate\":100,\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(0)).andExpect(jsonPath("$.vatRate").value(100));
        send(patch(BASE + "/" + id), "{\"basePrice\":null,\"vatRate\":null,\"name\":\"Renamed\",\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.vatRate").value(100));
        send(post(BASE), create("null", "10")).andExpect(status().isBadRequest());
        send(post(BASE), create("5", "null")).andExpect(status().isBadRequest());
    }

    @Test
    void unquotedNonNumbersStayRejected() throws Exception {
        send(post(BASE), create("NaN", "10")).andExpect(status().isBadRequest());
        send(post(BASE), create("Infinity", "10")).andExpect(status().isBadRequest());
        send(post(BASE), create("5", "-Infinity")).andExpect(status().isBadRequest());
        send(post(BASE), create("1e999999999", "10")).andExpect(status().isBadRequest());
        assertEquals(0, products());
    }

    // ---- version ----

    @ParameterizedTest
    @ValueSource(strings = {"\"0\"", "\"1\"", "0.0", "1.0", "0.9", "1.5", "1e0", "true", "[0]", "99999999999999999999"})
    void aVersionMustBeAWholeJsonNumberOnEveryProductCommand(String version) throws Exception {
        UUID id = seeded();
        var before = row(id);
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":" + version + "}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/active"), "{\"isActive\":false,\"version\":" + version + "}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true,\"version\":" + version + "}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":" + version + "}")
                .andExpect(status().isBadRequest());
        assertEquals(before, row(id));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM product_availability WHERE product_id = ?", String.class, id));
    }

    @Test
    void aFractionalVersionIsNoLongerCutOffIntoTheCurrentOne() throws Exception {
        UUID id = seeded();
        // Before: 0.9 was silently truncated to 0 and matched the stored version 0.
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":0.9}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":0}").andExpect(status().isOk());
    }

    @Test
    void wholeJsonNumberVersionsStillWorkAndStaleOnesAreStill409() throws Exception {
        UUID id = seeded();
        send(patch(BASE + "/" + id), "{\"name\":\"A\",\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        send(patch(BASE + "/" + id + "/active"), "{\"isActive\":false,\"version\":1}").andExpect(status().isOk());
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true,\"version\":2}").andExpect(status().isOk());
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0}").andExpect(status().isOk());
        send(patch(BASE + "/" + id), "{\"name\":\"B\",\"version\":0}").andExpect(status().isConflict());
        send(patch(BASE + "/" + id), "{\"name\":\"B\",\"version\":-1}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"name\":\"B\"}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"name\":\"B\",\"version\":null}").andExpect(status().isBadRequest());
    }

    @Test
    void theErrorBodyStaysAFixedProblemWithoutTheSubmittedValue() throws Exception {
        String body = send(post(BASE), create("\"SUPER-MARKER-9\"", "10")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("SUPER-MARKER-9"), body);
        assertFalse(body.toLowerCase().contains("exception"));
        assertFalse(body.contains("tools.jackson"));
    }
}
