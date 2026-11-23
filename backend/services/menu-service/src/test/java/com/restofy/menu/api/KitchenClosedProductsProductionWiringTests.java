package com.restofy.menu.api;

import com.restofy.menu.application.service.KitchenClosedProductsService;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.PublicMenuFixture.Spec;
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

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * No test caller imported: this is the production wiring until a verified Kitchen service identity exists. Every
 * closed-products query is refused as unauthenticated and there is no HTTP route that answers one: forged identity headers
 * (X-Restaurant-Id, X-Role, X-Service-Name, a staff session, a CSRF token) open nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenClosedProductsProductionWiringTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired KitchenClosedProductsService closed;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        fixture.product(RESTAURANT, fixture.root(RESTAURANT, "Main"), "Pide", Spec.visible().availability("OUT_OF_STOCK"));
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void everyQueryIsRefusedAsUnauthenticated() {
        assertThrows(UnauthenticatedException.class,
                () -> closed.list(Set.of(AvailabilityStatus.OUT_OF_STOCK, AvailabilityStatus.TEMPORARILY_CLOSED), 0, 20));
        assertThrows(UnauthenticatedException.class, () -> closed.list(null, -1, 0));
    }

    @Test
    void thereIsNoHttpRouteForClosedProductQueriesAndForgedHeadersOpenNothing() throws Exception {
        for (String path : new String[] {"/api/v1/internal/menu/closed-products", "/api/v1/menu/internal/closed-products",
                "/internal/v1/menu/closed-products", "/api/v1/menu/closed-products", "/api/v1/menu/products/closed",
                "/api/v1/kitchen/products/closed"}) {
            for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {get(path), get(path).with(user("kitchen")),
                    post(path).with(csrf()), post(path).with(user("kitchen")).with(csrf())}) {
                int status = mvc.perform(request.header("X-Role", "KITCHEN").header("X-Restaurant-Id", RESTAURANT)
                        .header("X-Service-Name", "kitchen-service").header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
                assertTrue(status == 401 || status == 403 || status == 404 || status == 405, path + " answered " + status);
            }
        }
    }
}
