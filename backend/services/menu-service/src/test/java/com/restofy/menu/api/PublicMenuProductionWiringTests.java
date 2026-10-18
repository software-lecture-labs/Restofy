package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import com.restofy.menu.support.PublicMenuFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No test resolver imported: this is the production wiring until the QR / Admin verification exists. The public
 * menu must refuse every restaurant reference, even the real UUID of a restaurant that has a full menu and even
 * when the client sends identity headers. Nothing of that restaurant may appear in any answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicMenuProductionWiringTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @Autowired PublicRestaurantScopeResolver resolver;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        UUID category = fixture.root(RESTAURANT, "Secret category");
        fixture.visible(RESTAURANT, category, "Secret product");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void theProductionResolverVerifiesNothing() {
        assertTrue(resolver.resolve(RESTAURANT.toString()).isEmpty());
        assertTrue(resolver.resolve("alpha-menu").isEmpty());
        assertTrue(resolver.resolve("").isEmpty());
    }

    @Test
    void everyPublicEndpointAnswers404AndLeaksNothing() throws Exception {
        for (String ref : new String[] {RESTAURANT.toString(), "alpha-menu", "unknown"}) {
            for (String path : new String[] {"/categories", "/products", "/products?q=secret", "/products?categoryId=" + UUID.randomUUID()}) {
                String body = mvc.perform(get("/api/v1/menu/public/restaurants/" + ref + path)
                                .header("X-Restaurant-Id", RESTAURANT).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID()))
                        .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
                assertEquals("The requested menu was not found.", JsonPath.read(body, "$.detail"));
                assertFalse(body.toLowerCase().contains("secret"), body);
            }
        }
    }

    @Test
    void theAnswerIsTheSameForARealAndAnUnknownReference() throws Exception {
        String real = mvc.perform(get("/api/v1/menu/public/restaurants/" + RESTAURANT + "/products")).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(get("/api/v1/menu/public/restaurants/" + UUID.randomUUID() + "/products")).andReturn().getResponse().getContentAsString();
        for (String field : new String[] {"status", "title", "detail"}) {
            assertEquals((Object) JsonPath.read(real, "$." + field), JsonPath.read(unknown, "$." + field), field);
        }
    }

    @Test
    void anUnverifiedReferenceIsNotFoundWhateverTheFiltersSay() throws Exception {
        String base = "/api/v1/menu/public/restaurants/" + RESTAURANT;
        mvc.perform(get(base + "/products?q=x")).andExpect(status().isNotFound());
        mvc.perform(get(base + "/products?q=" + "x".repeat(60))).andExpect(status().isNotFound());
        mvc.perform(get(base + "/products?categoryId=" + UUID.randomUUID() + "&subcategoryId=" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/menu/public/restaurants/" + "x".repeat(500) + "/categories")).andExpect(status().isNotFound());
    }
}
