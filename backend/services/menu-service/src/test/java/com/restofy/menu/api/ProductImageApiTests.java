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
 * Set / replace / remove of the single main image reference through {@code PATCH /products/{id}}. The allowed
 * origins below are RFC 2606 reserved test names configured for this context only: the application itself ships
 * with an EMPTY allowlist (see {@link ProductImageDisabledByDefaultApiTests}).
 */
@SpringBootTest(properties = "restofy.menu.image.allowed-origins=https://images.example,https://cdn.example:8443")
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductImageApiTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String BASE = "/api/v1/menu/products";
    private static final String IMAGE = "https://images.example/products/tea.png";
    private static final String OTHER_IMAGE = "https://cdn.example:8443/p/tea-2.webp";

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

    private UUID created() throws Exception {
        String body = "{\"categoryId\":\"" + category + "\",\"name\":\"Tea\",\"description\":\"Hot\",\"ingredientsText\":\"Leaves, water\","
                + "\"basePrice\":5,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}";
        String json = send(post(BASE), body).andExpect(status().isCreated()).andExpect(jsonPath("$.imageUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private ResultActions image(UUID id, String valueJson, long version) throws Exception {
        return send(patch(BASE + "/" + id), "{\"imageUrl\":" + valueJson + ",\"version\":" + version + "}");
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
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

    // ---- set / replace / remove ----

    @Test
    void addReplaceAndRemoveWalkThroughTheWholeLifecycle() throws Exception {
        UUID id = created();
        image(id, quoted(IMAGE), 0).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(IMAGE)).andExpect(jsonPath("$.version").value(1));
        assertEquals(IMAGE, row(id).get("image_url"));
        image(id, quoted(OTHER_IMAGE), 1).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(OTHER_IMAGE)).andExpect(jsonPath("$.version").value(2));
        assertEquals(OTHER_IMAGE, row(id).get("image_url"));
        image(id, "\"\"", 2).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist()).andExpect(jsonPath("$.version").value(3));
        assertNull(row(id).get("image_url"));
        assertEquals(1, products(), "removing the image never deletes the product");
    }

    @Test
    void removingNeverTouchesAnyOtherFieldOfTheProduct() throws Exception {
        UUID id = created();
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true,\"version\":0}").andExpect(status().isOk());
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0}").andExpect(status().isOk());
        image(id, quoted(IMAGE), 1).andExpect(status().isOk());
        var before = row(id);
        var availability = jdbc.queryForMap("SELECT * FROM product_availability WHERE product_id = ?", id);
        int history = jdbc.queryForObject("SELECT count(*) FROM product_availability_history WHERE product_id = ?", Integer.class, id);

        image(id, "\"\"", 2).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.isPublished").value(true)).andExpect(jsonPath("$.availability.status").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.ingredientsText").value("Leaves, water")).andExpect(jsonPath("$.description").value("Hot"));

        var after = row(id);
        for (String column : List.of("name", "description", "ingredients_text", "base_price", "currency", "vat_rate", "price_includes_vat",
                "is_active", "is_published", "category_id", "restaurant_id", "created_by_user_id", "created_at")) {
            assertEquals(before.get(column), after.get(column), column + " is untouched by an image removal");
        }
        assertEquals(availability, jdbc.queryForMap("SELECT * FROM product_availability WHERE product_id = ?", id));
        assertEquals(history, jdbc.queryForObject("SELECT count(*) FROM product_availability_history WHERE product_id = ?", Integer.class, id));
    }

    @Test
    void settingAndReplacingNeverTouchesPriceVatCategoryAvailabilityOrIngredients() throws Exception {
        UUID id = created();
        var before = row(id);
        image(id, quoted(IMAGE), 0).andExpect(status().isOk());
        image(id, quoted(OTHER_IMAGE), 1).andExpect(status().isOk());
        var after = row(id);
        for (String column : List.of("name", "description", "ingredients_text", "base_price", "currency", "vat_rate", "price_includes_vat",
                "is_active", "is_published", "category_id")) {
            assertEquals(before.get(column), after.get(column), column);
        }
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM product_availability WHERE product_id = ?", String.class, id));
    }

    @Test
    void removingWhenThereIsNoImageIsAnIdempotentNoOpWithTheCurrentVersion() throws Exception {
        UUID id = created();
        var before = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN); // a different admin
        image(id, "\"\"", 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.imageUrl").doesNotExist());
        image(id, "\"   \"", 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        assertEquals(before, row(id), "version, updated_at and updated_by did not move");
    }

    @Test
    void removingAnAlreadyRemovedImageWithTheNewVersionIsAlsoANoOp() throws Exception {
        UUID id = created();
        image(id, quoted(IMAGE), 0).andExpect(status().isOk());
        image(id, "\"\"", 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        image(id, "\"\"", 2).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertNull(row(id).get("image_url"));
    }

    @Test
    void anAbsentOrNullImageLeavesTheExistingOneAlone() throws Exception {
        UUID id = created();
        image(id, quoted(IMAGE), 0).andExpect(status().isOk());
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"version\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(IMAGE));
        send(patch(BASE + "/" + id), "{\"imageUrl\":null,\"description\":\"Cold\",\"version\":2}").andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(IMAGE));
        send(patch(BASE + "/" + id), "{\"imageUrl\":null,\"version\":3}").andExpect(status().isBadRequest());
        assertEquals(IMAGE, row(id).get("image_url"));
    }

    @Test
    void repeatingTheSameUrlIsANoOpAndTheCanonicalFormIsWhatIsStored() throws Exception {
        UUID id = created();
        image(id, quoted(IMAGE), 0).andExpect(status().isOk());
        image(id, quoted("  " + IMAGE + "  "), 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        image(id, quoted("HTTPS://IMAGES.EXAMPLE:443/products/tea.png"), 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.imageUrl").value(IMAGE));
        UUID second = created();
        image(second, quoted("HTTPS://Cdn.Example:8443/Dir/Tea.WEBP"), 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://cdn.example:8443/Dir/Tea.WEBP"));
    }

    @Test
    void aRealChangeRecordsTheActingAdminAndKeepsTheCreator() throws Exception {
        UUID id = created();
        var before = row(id);
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        image(id, quoted(IMAGE), 0).andExpect(status().isOk());
        var after = row(id);
        assertEquals(1L, after.get("version"));
        assertNotEquals(before.get("updated_by_user_id"), after.get("updated_by_user_id"));
        assertEquals(before.get("created_by_user_id"), after.get("created_by_user_id"));
        assertTrue(((java.sql.Timestamp) after.get("updated_at")).after((java.sql.Timestamp) before.get("updated_at")));
    }

    @Test
    void imageCanBeChangedTogetherWithOtherFieldsInOneAtomicUpdate() throws Exception {
        UUID id = created();
        send(patch(BASE + "/" + id), "{\"imageUrl\":\"" + IMAGE + "\",\"name\":\"Tea 2\",\"basePrice\":0,\"ingredientsText\":\"New\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(IMAGE)).andExpect(jsonPath("$.name").value("Tea 2"))
                .andExpect(jsonPath("$.basePrice").value(0)).andExpect(jsonPath("$.ingredientsText").value("New")).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aPartiallyInvalidUpdateAppliesNoneOfItIncludingTheImage() throws Exception {
        UUID id = created();
        var before = row(id);
        send(patch(BASE + "/" + id), "{\"imageUrl\":\"" + IMAGE + "\",\"basePrice\":-1,\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"imageUrl\":\"" + IMAGE + "\",\"currency\":\"XXX\",\"version\":0}").andExpect(status().isBadRequest());
        send(patch(BASE + "/" + id), "{\"imageUrl\":\"https://evil.example/a.png\",\"name\":\"Renamed\",\"version\":0}").andExpect(status().isBadRequest());
        assertEquals(before, row(id), "the valid image in the same request was not applied either");
    }

    // ---- every response carries the image consistently ----

    @Test
    void getListAndEveryCommandResponseCarryTheSameImageReference() throws Exception {
        UUID id = created();
        image(id, quoted(IMAGE), 0).andExpect(status().isOk());
        read(id).andExpect(jsonPath("$.imageUrl").value(IMAGE));
        mvc.perform(get(BASE).with(user("staff"))).andExpect(jsonPath("$.items[0].imageUrl").value(IMAGE));
        send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true,\"version\":1}").andExpect(jsonPath("$.imageUrl").value(IMAGE));
        send(patch(BASE + "/" + id + "/active"), "{\"isActive\":false,\"version\":2}").andExpect(jsonPath("$.imageUrl").value(IMAGE));
        send(patch(BASE + "/" + id + "/availability"), "{\"status\":\"TEMPORARILY_CLOSED\",\"reasonCode\":\"CLEANING\",\"version\":0}")
                .andExpect(jsonPath("$.imageUrl").value(IMAGE));
        mvc.perform(get(BASE + "?isActive=false").with(user("staff"))).andExpect(jsonPath("$.items[0].imageUrl").value(IMAGE));
    }

    @Test
    void theKeyIsAlwaysPresentWithNullWhenThereIsNoImage() throws Exception {
        UUID id = created();
        Map<String, Object> response = JsonPath.read(read(id).andReturn().getResponse().getContentAsString(), "$");
        assertTrue(response.containsKey("imageUrl"));
        assertNull(response.get("imageUrl"));
    }

    @Test
    void anImageIsNotSettableOnCreateAndThatIsSaidClearly() throws Exception {
        String body = "{\"categoryId\":\"" + category + "\",\"name\":\"Tea\",\"imageUrl\":\"" + IMAGE + "\",\"basePrice\":5,\"currency\":\"TRY\","
                + "\"vatRate\":10,\"priceIncludesVat\":true}";
        send(post(BASE), body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("imageUrl"));
        assertEquals(0, products());
    }

    // ---- untrusted input ----

    @ParameterizedTest
    @ValueSource(strings = {
            "http://images.example/a.png", "javascript:alert(1)", "data:image/png;base64,AAAA", "file:///a.png", "https://evil.example/a.png",
            "https://images.example.evil.example/a.png", "https://images.example@evil.example/a.png", "https://u:p@images.example/a.png",
            "https://images.example/a.png?x=1", "https://images.example/a.png#f", "https://localhost/a.png", "https://127.0.0.1/a.png",
            "https://[::1]/a.png", "https://10.0.0.1/a.png", "https://images.example/a.svg", "https://images.example/a.gif",
            "https://images.example/../a.png", "https://images.example/%2e%2e/a.png", "https://images.example/a%2fb.png",
            "https://images.example/a%00.png", "https://images.example/a b.png", "https://images.example/a", "https://images.example/",
            "images.example/a.png", "//images.example/a.png", "https://images.example:8443/a.png", "https://cdn.example/a.png"})
    void untrustedReferencesAreA400AndChangeNothing(String url) throws Exception {
        UUID id = created();
        var before = row(id);
        String body = image(id, quoted(url), 0).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("evil.example"), "the submitted value is not echoed back");
        assertEquals(before, row(id));
        image(id, quoted(IMAGE), 0).andExpect(status().isOk()); // proves the product was fine and the failure was the URL
    }

    @Test
    void anOverlongUrlAndUnsafeCharactersAreA400() throws Exception {
        UUID id = created();
        String tooLong = "https://images.example/" + "a".repeat(2030) + ".png";
        image(id, quoted(tooLong), 0).andExpect(status().isBadRequest());
        image(id, "\"https://images.example/a\\u0000.png\"", 0).andExpect(status().isBadRequest());
        image(id, "\"https://images.example/a\\u001f.png\"", 0).andExpect(status().isBadRequest());
        image(id, "\"https://images.example/\\ud800.png\"", 0).andExpect(status().isBadRequest());
        assertNull(row(id).get("image_url"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "true", "[\"https://images.example/a.png\"]", "{\"u\":\"https://images.example/a.png\"}"})
    void aNonStringImageUrlIsA400(String raw) throws Exception {
        UUID id = created();
        image(id, raw, 0).andExpect(status().isBadRequest());
        assertNull(row(id).get("image_url"));
    }

    @Test
    void errorBodiesAreFixedTextWithoutTheValueOrTheAllowlist() throws Exception {
        UUID id = created();
        String body = image(id, quoted("https://evil.example/SECRET-MARKER.png"), 0).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertEquals("Image URL origin is not allowed.", JsonPath.read(body, "$.detail"));
        assertFalse(body.contains("SECRET-MARKER"));
        assertFalse(body.contains("images.example"), "the configured origins are not disclosed");
        assertFalse(body.contains("cdn.example"));
    }

    // ---- version, tenant, roles ----

    @Test
    void aStaleVersionIsA409ForSettingAndForRemoving() throws Exception {
        UUID id = created();
        image(id, quoted(IMAGE), 0).andExpect(status().isOk());
        image(id, quoted(OTHER_IMAGE), 0).andExpect(status().isConflict());
        image(id, "\"\"", 0).andExpect(status().isConflict());
        assertEquals(IMAGE, row(id).get("image_url"));
        assertEquals(1L, row(id).get("version"));
    }

    @Test
    void anotherRestaurantCanNeitherSeeSetNorRemoveMyImage() throws Exception {
        UUID id = created();
        image(id, quoted(IMAGE), 0).andExpect(status().isOk());
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        read(id).andExpect(status().isNotFound());
        image(id, quoted(OTHER_IMAGE), 1).andExpect(status().isNotFound());
        image(id, "\"\"", 1).andExpect(status().isNotFound());
        String list = mvc.perform(get(BASE).with(user("staff"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(IMAGE));
        assertEquals(IMAGE, row(id).get("image_url"));
    }

    @Test
    void onlyAnAdminMayChangeTheImageAndTheAnswerComesBeforeTheUrlIsValidated() throws Exception {
        UUID id = created();
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT_A, role);
            image(id, quoted(IMAGE), 0).andExpect(status().isForbidden());
            image(id, quoted("javascript:alert(1)"), 0).andExpect(status().isForbidden());
            image(id, "\"\"", 0).andExpect(status().isForbidden());
        }
        actor.actAsNobody();
        image(id, quoted(IMAGE), 0).andExpect(status().isUnauthorized());
        image(id, quoted("javascript:alert(1)"), 0).andExpect(status().isUnauthorized());
        assertNull(row(id).get("image_url"));
    }

    @Test
    void headersAndBodyFieldsCannotChooseTheTenantOrTheAllowedOrigin() throws Exception {
        UUID id = created();
        actor.actAs(RESTAURANT_B, ActorRole.ADMIN);
        mvc.perform(patch(BASE + "/" + id).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Restaurant-Id", RESTAURANT_A).header("X-Role", "ADMIN").header("X-Image-Origin", "https://evil.example")
                        .content("{\"imageUrl\":\"" + IMAGE + "\",\"version\":0}"))
                .andExpect(status().isNotFound());
        actor.actAs(RESTAURANT_A, ActorRole.ADMIN);
        mvc.perform(patch(BASE + "/" + id).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Image-Origin", "https://evil.example")
                        .content("{\"imageUrl\":\"https://evil.example/a.png\",\"allowedOrigins\":[\"https://evil.example\"],\"version\":0}"))
                .andExpect(status().isBadRequest());
        assertNull(row(id).get("image_url"));
    }

    @Test
    void serverControlledFieldsNextToTheImageAreNeverAssigned() throws Exception {
        UUID id = created();
        UUID forged = UUID.randomUUID();
        send(patch(BASE + "/" + id), "{\"imageUrl\":\"" + IMAGE + "\",\"version\":0,\"id\":\"" + forged + "\",\"createdByUserId\":\"" + forged
                + "\",\"updatedByUserId\":\"" + forged + "\",\"canOrder\":true,\"isVisibleToCustomer\":true}").andExpect(status().isOk());
        var stored = row(id);
        assertNotEquals(forged, stored.get("updated_by_user_id"));
        assertNotEquals(forged, stored.get("created_by_user_id"));
        assertEquals(id, stored.get("id"));
        send(patch(BASE + "/" + id), "{\"imageUrl\":\"" + OTHER_IMAGE + "\",\"restaurantId\":\"" + RESTAURANT_B + "\",\"version\":1}").andExpect(status().isBadRequest());
        assertEquals(IMAGE, row(id).get("image_url"));
    }
}
