package com.restofy.menu.integration;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.application.service.KitchenAvailabilityCommand;
import com.restofy.menu.application.service.KitchenAvailabilityCommandService;
import com.restofy.menu.application.service.KitchenAvailabilityResult;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.enums.CommandOutcome;
import com.restofy.menu.domain.exception.CommandIdConflictException;
import com.restofy.menu.domain.exception.ProductAvailabilityVersionConflictException;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import com.restofy.menu.support.TestKitchenCallerProvider;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MENU-INT-06 · journeys across Menu's three surfaces: the staff API, the Kitchen command handler and the customer menu.
 *
 * <p><b>This is not an end-to-end test of any other service.</b> Three collaborators are in-process test stand-ins that
 * live in {@code src/test} and are never packaged: the staff actor ({@link TestActorProvider}, stands in for Auth), the
 * Kitchen service caller ({@link TestKitchenCallerProvider}, stands in for the D-09 service identity that does not exist)
 * and the customer restaurant resolver ({@link TestPublicScopeConfiguration}, stands in for QR). What is real is Menu
 * itself: HTTP stack, security chain, services, Flyway schema and PostgreSQL. The tests check that the three surfaces
 * agree after each step, that identities of different kinds are never interchangeable, and that restaurants never see
 * or change each other's products. Single-surface rules are already covered by the per-feature suites.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class CrossSurfaceAvailabilityJourneyTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID BETA = TestPublicScopeConfiguration.RESTAURANT_BETA;
    private static final UUID COOK = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
    private static final String ADMIN_PRODUCTS = "/api/v1/menu/products/";
    private static final String ALPHA_PUBLIC = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF + "/products/";
    private static final String BETA_PUBLIC = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.BETA_REF + "/products/";
    private static final String REASON_TEXT = "Malzeme tükendi, yarın açılır";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider staff;
    @Autowired TestKitchenCallerProvider kitchenCaller;
    @Autowired KitchenAvailabilityCommandService kitchen;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID alphaProduct;
    private UUID betaProduct;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        alphaProduct = fixture.visible(ALPHA, fixture.root(ALPHA, "Main"), "Pide");
        betaProduct = fixture.visible(BETA, fixture.root(BETA, "Main"), "Lahmacun");
        staff.actAs(ALPHA, ActorRole.ADMIN);
        kitchenCaller.actAs(ALPHA, COOK);
    }

    @AfterEach
    void clean() {
        staff.actAsNobody();
        kitchenCaller.actAsNobody();
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability_commands");
        fixture.clean();
    }

    // ---- helpers ----

    private static KitchenAvailabilityCommand command(UUID commandId, UUID product, AvailabilityStatus status, String reasonCode,
            String reasonText, Long expectedVersion) {
        return new KitchenAvailabilityCommand(commandId, product, status, reasonCode, reasonText, expectedVersion);
    }

    private KitchenAvailabilityCommand close(UUID product) {
        return command(UUID.randomUUID(), product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", REASON_TEXT, null);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private String adminProduct(UUID product) throws Exception {
        return body(mvc.perform(get(ADMIN_PRODUCTS + product).with(user("staff"))).andExpect(status().isOk()).andReturn());
    }

    private String publicDetail(String base, UUID product) throws Exception {
        return body(mvc.perform(get(base + product)).andExpect(status().isOk()).andReturn());
    }

    private String adminSetAvailability(UUID product, String status, long version) throws Exception {
        String reason = "AVAILABLE".equals(status) ? "" : ",\"reasonCode\":\"CLEANING\"";
        return body(mvc.perform(patch(ADMIN_PRODUCTS + product + "/availability").with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"" + reason + ",\"version\":" + version + "}"))
                .andExpect(status().isOk()).andReturn());
    }

    private long liveVersion(UUID product) {
        return jdbc.queryForObject("SELECT version FROM product_availability WHERE product_id = ?", Long.class, product);
    }

    private String liveStatus(UUID product) {
        return jdbc.queryForObject("SELECT status FROM product_availability WHERE product_id = ?", String.class, product);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // ---- J-01: one change, three surfaces ----

    @Test
    void aKitchenClosureAndReopeningIsSeenIdenticallyByTheCustomerMenuAndTheStaffApi() throws Exception {
        assertTrue(JsonPath.<Boolean>read(publicDetail(ALPHA_PUBLIC, alphaProduct), "$.canOrder"));

        KitchenAvailabilityResult closed = kitchen.handle(close(alphaProduct));
        assertEquals(CommandOutcome.APPLIED, closed.outcome());
        assertFalse(closed.replayed());
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, closed.status());
        assertEquals(1L, closed.version());

        String customer = publicDetail(ALPHA_PUBLIC, alphaProduct);
        assertEquals("OUT_OF_STOCK", JsonPath.read(customer, "$.availability"), "still visible to the customer, but closed");
        assertFalse(JsonPath.<Boolean>read(customer, "$.canOrder"));

        String staffView = adminProduct(alphaProduct);
        assertEquals("OUT_OF_STOCK", JsonPath.read(staffView, "$.availability.status"));
        assertEquals("SOLD_OUT", JsonPath.read(staffView, "$.availability.reasonCode"));
        assertEquals(REASON_TEXT, JsonPath.read(staffView, "$.availability.reasonText"));
        assertEquals(1, JsonPath.<Number>read(staffView, "$.availability.version").intValue());
        assertFalse(JsonPath.<Boolean>read(staffView, "$.canOrder"));

        KitchenAvailabilityResult reopened = kitchen.handle(command(UUID.randomUUID(), alphaProduct, AvailabilityStatus.AVAILABLE, null,
                null, null));
        assertEquals(2L, reopened.version());
        assertTrue(JsonPath.<Boolean>read(publicDetail(ALPHA_PUBLIC, alphaProduct), "$.canOrder"));
        assertTrue(JsonPath.<Boolean>read(adminProduct(alphaProduct), "$.canOrder"));
        assertEquals(2, count("product_availability_history"), "one history row per real change");
    }

    @Test
    void theKitchensReasonAndTheCallersIdentityNeverReachTheCustomer() throws Exception {
        UUID commandId = UUID.randomUUID();
        kitchen.handle(command(commandId, alphaProduct, AvailabilityStatus.TEMPORARILY_CLOSED, "SOLD_OUT", REASON_TEXT, null));

        String detail = publicDetail(ALPHA_PUBLIC, alphaProduct);
        String list = body(mvc.perform(get("/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF + "/products"))
                .andExpect(status().isOk()).andReturn());
        for (String leaked : new String[] {REASON_TEXT, "SOLD_OUT", "Malzeme", ALPHA.toString(), COOK.toString(),
                commandId.toString(), "reasonCode", "reasonText", "restaurantId", "commandId"}) {
            assertFalse(detail.contains(leaked), "detail leaks " + leaked);
            assertFalse(list.contains(leaked), "list leaks " + leaked);
        }
    }

    // ---- J-02: replay is a historical receipt ----

    @Test
    void aReplayedKitchenCommandReturnsItsOriginalReceiptAndNeverOverwritesALaterStaffChange() throws Exception {
        KitchenAvailabilityCommand closing = close(alphaProduct);
        KitchenAvailabilityResult first = kitchen.handle(closing);
        adminSetAvailability(alphaProduct, "AVAILABLE", first.version());
        assertEquals("AVAILABLE", liveStatus(alphaProduct));

        KitchenAvailabilityResult replay = kitchen.handle(closing);

        assertTrue(replay.replayed());
        assertEquals(first.outcome(), replay.outcome());
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, replay.status(), "the receipt is what happened then");
        assertEquals(first.version(), replay.version());
        assertEquals("AVAILABLE", liveStatus(alphaProduct), "the live state is the staff member's, not re-applied");
        assertEquals(2L, liveVersion(alphaProduct));
        assertTrue(JsonPath.<Boolean>read(publicDetail(ALPHA_PUBLIC, alphaProduct), "$.canOrder"));
        assertEquals(2, count("product_availability_history"), "the kitchen change and the staff change, no third row");
        assertEquals(1, count("product_availability_commands"));
    }

    @Test
    void aKitchenCommandBasedOnAVersionThatAStaffChangeHasPassedIsAConflictAndWritesNothing() throws Exception {
        long seen = liveVersion(alphaProduct);
        adminSetAvailability(alphaProduct, "TEMPORARILY_CLOSED", seen);
        assertEquals(seen + 1, liveVersion(alphaProduct));

        KitchenAvailabilityCommand stale = command(UUID.randomUUID(), alphaProduct, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null,
                seen);
        assertThrows(ProductAvailabilityVersionConflictException.class, () -> kitchen.handle(stale));

        assertEquals("TEMPORARILY_CLOSED", liveStatus(alphaProduct));
        assertEquals(seen + 1, liveVersion(alphaProduct));
        assertEquals(0, count("product_availability_commands"), "a refused command leaves no record, so a corrected retry is possible");
        assertEquals(1, count("product_availability_history"), "only the staff change");
    }

    @Test
    void reusingACommandIdForADifferentClosureIsAConflictEvenAfterTheStaffChangedTheProduct() throws Exception {
        UUID id = UUID.randomUUID();
        KitchenAvailabilityResult first = kitchen.handle(command(id, alphaProduct, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, null));
        adminSetAvailability(alphaProduct, "AVAILABLE", first.version());

        assertThrows(CommandIdConflictException.class,
                () -> kitchen.handle(command(id, alphaProduct, AvailabilityStatus.TEMPORARILY_CLOSED, "CLEANING", null, null)));
        assertEquals("AVAILABLE", liveStatus(alphaProduct));
    }

    // ---- J-03: identities of different kinds are not interchangeable ----

    @Test
    void aVerifiedStaffMemberIsNotAKitchenServiceAndNothingIsWritten() throws Exception {
        kitchenCaller.actAsNobody();
        long before = liveVersion(alphaProduct);

        assertThrows(UnauthenticatedException.class, () -> kitchen.handle(close(alphaProduct)));

        assertEquals(before, liveVersion(alphaProduct));
        assertEquals(0, count("product_availability_commands"));
        assertEquals(0, count("product_availability_history"));
        adminProduct(alphaProduct);
    }

    @Test
    void aKitchenServiceCallerIsNotAStaffMemberEvenWhenTheRequestClaimsToBeAnAdmin() throws Exception {
        staff.actAsNobody();
        long before = liveVersion(alphaProduct);

        mvc.perform(get(ADMIN_PRODUCTS + alphaProduct).with(user("staff")).header("X-Role", "ADMIN")
                .header("X-Restaurant-Id", ALPHA).header("X-User-Id", COOK).header("X-Service-Name", "kitchen-service"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(ADMIN_PRODUCTS + alphaProduct + "/availability").with(user("staff")).with(csrf()).header("X-Role", "ADMIN")
                .header("X-Restaurant-Id", ALPHA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":" + before + "}"))
                .andExpect(status().isUnauthorized());

        assertEquals(before, liveVersion(alphaProduct));
        assertEquals("AVAILABLE", liveStatus(alphaProduct));
    }

    @Test
    void aWaiterOrKitchenStaffMemberCannotUseTheStaffApiToCloseAProduct() throws Exception {
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            staff.actAs(ALPHA, role);
            mvc.perform(patch(ADMIN_PRODUCTS + alphaProduct + "/availability").with(user("staff")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0}")).andExpect(status().isForbidden());
        }
        assertEquals("AVAILABLE", liveStatus(alphaProduct));
        assertEquals(0L, liveVersion(alphaProduct));
    }

    // ---- J-04: restaurant isolation on every surface ----

    @Test
    void anotherRestaurantCanNeitherChangeNorSeeTheProductOnAnySurface() throws Exception {
        long before = liveVersion(alphaProduct);

        // Kitchen of restaurant B names restaurant A's product: the same refusal as for a product that does not exist.
        kitchenCaller.actAs(BETA, COOK);
        ProductNotFoundException foreign = assertThrows(ProductNotFoundException.class, () -> kitchen.handle(close(alphaProduct)));
        ProductNotFoundException unknown = assertThrows(ProductNotFoundException.class, () -> kitchen.handle(close(UUID.randomUUID())));
        assertEquals(unknown.getMessage().replaceAll("[0-9a-f-]{36}", "<id>"), foreign.getMessage().replaceAll("[0-9a-f-]{36}", "<id>"));

        // Staff of restaurant B.
        staff.actAs(BETA, ActorRole.ADMIN);
        mvc.perform(get(ADMIN_PRODUCTS + alphaProduct).with(user("staff"))).andExpect(status().isNotFound());
        mvc.perform(patch(ADMIN_PRODUCTS + alphaProduct + "/availability").with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":" + before + "}"))
                .andExpect(status().isNotFound());

        // Customer of restaurant B: same 404 as for an unknown product.
        String foreignBody = body(mvc.perform(get(BETA_PUBLIC + alphaProduct)).andExpect(status().isNotFound()).andReturn());
        String unknownBody = body(mvc.perform(get(BETA_PUBLIC + UUID.randomUUID())).andExpect(status().isNotFound()).andReturn());
        // The only difference allowed is the echoed request URL ("instance"), which is the caller's own input.
        assertEquals(unknownBody.replaceAll("\"instance\":\"[^\"]*\",?", ""), foreignBody.replaceAll("\"instance\":\"[^\"]*\",?", ""));

        assertEquals(before, liveVersion(alphaProduct));
        assertEquals(0, count("product_availability_commands"));
        assertTrue(JsonPath.<Boolean>read(publicDetail(ALPHA_PUBLIC, alphaProduct), "$.canOrder"));
    }

    @Test
    void closingOneRestaurantsProductLeavesTheOthersUntouchedAndCommandIdsAreNotSharedAcrossRestaurants() throws Exception {
        UUID shared = UUID.randomUUID();
        KitchenAvailabilityResult alpha = kitchen.handle(command(shared, alphaProduct, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null,
                null));
        kitchenCaller.actAs(BETA, COOK);
        KitchenAvailabilityResult beta = kitchen.handle(command(shared, betaProduct, AvailabilityStatus.TEMPORARILY_CLOSED, "CLEANING", null,
                null));

        assertFalse(alpha.replayed());
        assertFalse(beta.replayed(), "the same command id in another restaurant is a new command, not a replay or a conflict");
        assertEquals("OUT_OF_STOCK", liveStatus(alphaProduct));
        assertEquals("TEMPORARILY_CLOSED", liveStatus(betaProduct));
        assertEquals("OUT_OF_STOCK", JsonPath.read(publicDetail(ALPHA_PUBLIC, alphaProduct), "$.availability"));
        assertEquals("TEMPORARILY_CLOSED", JsonPath.read(publicDetail(BETA_PUBLIC, betaProduct), "$.availability"));
    }

    // ---- J-05: staff and Kitchen writing at the same time ----

    @Test
    void staffAndKitchenChangingTheSameProductAtOnceKeepTheVersionHistoryAndFinalStateConsistent() throws Exception {
        List<Object> outcomes = race(12, i -> {
            if (i % 2 == 0) {
                AvailabilityStatus target = i % 4 == 0 ? AvailabilityStatus.OUT_OF_STOCK : AvailabilityStatus.AVAILABLE;
                return kitchen.handle(command(UUID.randomUUID(), alphaProduct, target, target == AvailabilityStatus.AVAILABLE ? null
                        : "REASON_" + i, null, null));
            }
            try {
                String target = i % 3 == 0 ? "TEMPORARILY_CLOSED" : "AVAILABLE";
                String reason = "AVAILABLE".equals(target) ? "" : ",\"reasonCode\":\"CLEANING\"";
                return mvc.perform(patch(ADMIN_PRODUCTS + alphaProduct + "/availability").with(user("staff")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\"" + reason + ",\"version\":"
                                + liveVersion(alphaProduct) + "}")).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                return e;
            }
        });

        for (int i = 0; i < outcomes.size(); i++) {
            Object outcome = outcomes.get(i);
            if (i % 2 == 0) {
                assertTrue(outcome instanceof KitchenAvailabilityResult, "a kitchen command without expectedVersion always succeeds: " + outcome);
            } else {
                assertTrue(outcome.equals(200) || outcome.equals(409), "a staff change is applied or a clean version conflict: " + outcome);
            }
        }
        long version = liveVersion(alphaProduct);
        assertEquals(version, count("product_availability_history"), "one version and one history row per real change, whoever made it");
        String lastInHistory = jdbc.queryForObject("SELECT to_status FROM product_availability_history ORDER BY changed_at DESC, id DESC "
                + "LIMIT 1", String.class);
        assertEquals(lastInHistory, liveStatus(alphaProduct), "the live state is the last recorded change");
        assertEquals(liveStatus(alphaProduct), JsonPath.read(publicDetail(ALPHA_PUBLIC, alphaProduct), "$.availability"));
    }

    private List<Object> race(int contenders, IntFunction<Object> attempt) throws Exception {
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return attempt.apply(index);
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException unexpected) {
                    fail("Unexpected failure: " + unexpected.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
