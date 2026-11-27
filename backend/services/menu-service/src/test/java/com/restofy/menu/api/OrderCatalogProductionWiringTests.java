package com.restofy.menu.api;

import com.restofy.menu.application.security.TrustedOrderCallerProvider;
import com.restofy.menu.application.service.OrderCatalogService;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.support.PublicMenuFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * No test caller imported and NO Order key configured: this is the production default. Every lookup is refused as
 * unauthenticated, whatever it asks, and the HTTP route (a signed Order credential is the only way in) answers 401 to everyone:
 * forged identity headers (X-Restaurant-Id, X-Role, X-Service-Name, a staff session, a CSRF token) open nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCatalogProductionWiringTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired OrderCatalogService catalog;
    @Autowired TrustedOrderCallerProvider callers;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID product;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        product = fixture.visible(RESTAURANT, fixture.root(RESTAURANT, "Main"), "Pide");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void theProductionProviderNeverVouchesForAnyone() {
        assertTrue(callers.current().isEmpty());
    }

    @Test
    void everyLookupIsRefusedAsUnauthenticated() {
        assertThrows(UnauthenticatedException.class, () -> catalog.lookup(RESTAURANT, List.of(product)));
        assertThrows(UnauthenticatedException.class, () -> catalog.lookup(RESTAURANT, List.of()));
        assertThrows(UnauthenticatedException.class, () -> catalog.lookup(null, null));
    }

    @Test
    void thereIsNoHttpRouteForCatalogLookupsAndForgedHeadersOpenNothing() throws Exception {
        String body = "{\"restaurantId\":\"" + RESTAURANT + "\",\"productIds\":[\"" + product + "\"]}";
        for (String path : new String[] {"/api/v1/internal/menu/catalog/products:lookup", "/api/v1/internal/menu/catalog/products",
                "/api/v1/menu/internal/catalog/products", "/internal/v1/menu/catalog/products",
                "/api/v1/menu/catalog/products:lookup", "/api/v1/menu/catalog/products/" + product}) {
            for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {post(path).with(csrf()),
                    post(path).with(user("order")).with(csrf()), get(path), get(path).with(user("order"))}) {
                int status = mvc.perform(request.header("X-Role", "ADMIN").header("X-Restaurant-Id", RESTAURANT)
                        .header("X-Service-Name", "order-service").header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
                assertTrue(status == 401 || status == 403 || status == 404 || status == 405, path + " answered " + status);
            }
        }
    }
}
