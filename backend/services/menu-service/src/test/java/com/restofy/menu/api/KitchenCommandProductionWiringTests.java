package com.restofy.menu.api;

import com.restofy.menu.application.security.TrustedKitchenCallerProvider;
import com.restofy.menu.application.service.KitchenAvailabilityCommand;
import com.restofy.menu.application.service.KitchenAvailabilityCommandService;
import com.restofy.menu.domain.enums.AvailabilityStatus;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * No test caller imported: this is the production wiring until a verified Kitchen service identity exists. No command
 * can be processed, whatever the command says, and there is no HTTP route that accepts one. Nothing written, nothing
 * changed, and forged identity headers change nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenCommandProductionWiringTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired KitchenAvailabilityCommandService kitchen;
    @Autowired TrustedKitchenCallerProvider callers;
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
        jdbc.update("DELETE FROM product_availability_commands");
        fixture.clean();
    }

    private String state() {
        return jdbc.queryForObject("SELECT status || '/' || version FROM product_availability WHERE product_id = ?", String.class, product);
    }

    @Test
    void theProductionProviderNeverVouchesForAnyone() {
        assertTrue(callers.current().isEmpty());
    }

    @Test
    void everyCommandIsRefusedAsUnauthenticatedAndNothingIsWritten() {
        String before = state();
        for (AvailabilityStatus status : AvailabilityStatus.values()) {
            KitchenAvailabilityCommand command = new KitchenAvailabilityCommand(UUID.randomUUID(), product, status, "SOLD_OUT", null, null);
            assertThrows(UnauthenticatedException.class, () -> kitchen.handle(command));
        }
        assertEquals(before, state());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product_availability_commands", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product_availability_history", Integer.class));
    }

    @Test
    void aMissingOrMalformedCommandIsAlsoJustUnauthenticated() {
        assertThrows(UnauthenticatedException.class, () -> kitchen.handle(null));
        KitchenAvailabilityCommand empty = new KitchenAvailabilityCommand(null, null, null, null, null, null);
        assertThrows(UnauthenticatedException.class, () -> kitchen.handle(empty));
    }

    @Test
    void thereIsNoHttpRouteForKitchenCommandsAndForgedHeadersOpenNothing() throws Exception {
        String before = state();
        String body = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product + "\",\"targetStatus\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\"}";
        for (String path : new String[] {"/api/v1/menu/internal/availability-commands", "/internal/v1/menu/availability-commands",
                "/api/v1/menu/availability-commands", "/api/v1/menu/kitchen/availability", "/api/v1/menu/products/" + product + "/kitchen-availability"}) {
            for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                    post(path).with(csrf()), post(path).with(user("kitchen")).with(csrf()), put(path).with(user("kitchen")).with(csrf()), get(path)}) {
                int status = mvc.perform(request.header("X-Role", "KITCHEN").header("X-Restaurant-Id", RESTAURANT)
                        .header("X-Service-Name", "kitchen-service").header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
                assertTrue(status == 401 || status == 403 || status == 404 || status == 405, path + " answered " + status);
            }
        }
        assertEquals(before, state());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product_availability_commands", Integer.class));
    }
}
