package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.application.security.GuestSessionVerification;
import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.application.security.GuestToken;
import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import com.restofy.menu.support.PublicMenuFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The production wiring with no test configuration: QR's guest-session verification is not connected, so even a
 * well-formed guest cookie and the real UUID of a restaurant with a full menu read nothing. The public menu stays
 * fail-closed until the QR contract and the service identity (D-09) exist; this test is the guard that nobody wires a
 * "verifier" that accepts a cookie on its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicGuestSessionProductionWiringTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String GUEST = "A".repeat(43);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired GuestSessionVerifier verifier;
    @Autowired PublicRestaurantScopeResolver resolver;

    private PublicMenuFixture fixture;
    private UUID product;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        product = fixture.visible(RESTAURANT, fixture.root(RESTAURANT, "Secret category"), "Secret product");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    @Test
    void theProductionVerifierIsNotConnectedAndRefusesEveryCredential() {
        assertEquals("NotConnectedGuestSessionVerifier", verifier.getClass().getSimpleName());
        assertEquals(new GuestSessionVerification.Rejected(), verifier.verify(new GuestToken(GUEST)));
        assertEquals("GuestSessionRestaurantScopeResolver", resolver.getClass().getSimpleName());
        assertTrue(resolver.resolve(RESTAURANT.toString()).isEmpty());
    }

    @Test
    void everyCustomerEndpointStaysNotFoundEvenWithAWellFormedGuestCookieAndTheRealRestaurantId() throws Exception {
        for (String path : new String[] {"/categories", "/products", "/products?q=secret", "/products/" + product}) {
            String body = mvc.perform(get("/api/v1/menu/public/restaurants/" + RESTAURANT + path)
                            .cookie(new Cookie("restofy_guest", GUEST))
                            .header("X-Restaurant-Id", RESTAURANT).header("X-Role", "ADMIN"))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertEquals("The requested menu was not found.", JsonPath.read(body, "$.detail"));
            assertFalse(body.toLowerCase().contains("secret"), body);
            assertFalse(body.contains(GUEST), body);
        }
    }
}
