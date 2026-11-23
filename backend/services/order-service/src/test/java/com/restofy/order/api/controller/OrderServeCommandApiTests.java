package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.ServeOrderCommandRequest;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.restofy.order.infrastructure.client.mock.DevFixtures.RESTAURANT_A;
import static com.restofy.order.infrastructure.client.mock.DevFixtures.TABLE_SESSION_A1;
import static com.restofy.order.infrastructure.client.mock.DevFixtures.USER_WAITER_A;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderServeCommandApiTests extends AbstractCancellationApiTest {

    private static final String BASE = "/api/v1/internal/orders/";
    private static final String DELEGATION = "X-Restofy-Staff-Delegation";
    private static final String WAITER = DevFixtures.STAFF_WAITER_A;
    private static final KeyPair SIGNER = Jwts.SIG.RS256.keyPair().build();

    @DynamicPropertySource
    static void configureWaiterTrust(DynamicPropertyRegistry properties) {
        properties.add("ORDER_WAITER_SERVICE_PUBLIC_KEYS", () ->
                "{\"test\":\"" + Base64.getEncoder().encodeToString(SIGNER.getPublic().getEncoded()) + "\"}");
        properties.add("ORDER_WAITER_SERVICE_RESTAURANTS", RESTAURANT_A::toString);
    }

    @Test
    void appliesOnlyOnceAndReturnsTheRecordedDecisionAfterTheOrderAdvances() throws Exception {
        UUID order = readyOrder();
        long beforeVersion = versionOf(order);
        int beforeEvents = changeEvents(order);
        var command = command(TABLE_SESSION_A1, beforeVersion);

        var first = body(serve(order, command).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED")));
        assertEquals("SERVED", fulfillmentOf(order));
        assertEquals(beforeVersion + 1, versionOf(order));
        assertEquals(beforeEvents + 1, changeEvents(order));
        assertEquals(USER_WAITER_A.toString(), jdbc.queryForObject("""
                SELECT actor_id FROM order_status_history WHERE source_event_id = ? AND order_id = ?
                """, String.class, command.commandId(), order));

        patchStatus(staff(WAITER), order, "COMPLETED").andExpect(status().isOk());
        var repeated = body(serve(order, command).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE")));
        assertEquals(first.path("orderVersion"), repeated.path("orderVersion"));
        assertEquals(first.path("servedAt"), repeated.path("servedAt"));
        assertEquals(beforeEvents + 2, changeEvents(order));
        assertEquals("COMPLETED", fulfillmentOf(order));

        query(order, command.commandId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.orderVersion").value(beforeVersion + 1));
    }

    @Test
    void replaysRejectionAndRefusesReusingItsIdForDifferentInput() throws Exception {
        UUID order = readyOrder();
        long version = versionOf(order);
        var command = command(UUID.randomUUID(), version);

        serve(order, command).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_SESSION_MISMATCH"));
        query(order, command.commandId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionCode").value("ORDER_SESSION_MISMATCH"));
        serve(order, command).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_SESSION_MISMATCH"));
        serve(order, new ServeOrderCommandRequest(command.commandId(), RESTAURANT_A, TABLE_SESSION_A1, version,
                USER_WAITER_A, command.requestedAt())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_COMMAND_CONFLICT"));
        query(order, UUID.randomUUID()).andExpect(status().isNotFound());
        assertEquals(version, versionOf(order));
        assertEquals("READY", fulfillmentOf(order));
    }

    @Test
    void concurrentCommandsWithTheSameExpectedVersionHaveOneWinner() throws Exception {
        UUID order = readyOrder();
        long version = versionOf(order);
        var first = command(TABLE_SESSION_A1, version);
        var second = command(TABLE_SESSION_A1, version);
        Callable<Integer> one = () -> serve(order, first).andReturn().getResponse().getStatus();
        Callable<Integer> two = () -> serve(order, second).andReturn().getResponse().getStatus();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(one, two));
            var statuses = List.of(results.get(0).get(), results.get(1).get()).stream().sorted().toList();
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(version + 1, versionOf(order));
        assertEquals("SERVED", fulfillmentOf(order));
    }

    @Test
    void concurrentUseOfTheSameCommandIdForDifferentOrdersRollsBackTheLosingTransition() throws Exception {
        UUID firstOrder = readyOrder();
        UUID secondOrder = readyOrder();
        var shared = command(TABLE_SESSION_A1, versionOf(firstOrder));
        assertEquals(shared.expectedVersion().longValue(), versionOf(secondOrder));
        Callable<Integer> one = () -> serve(firstOrder, shared).andReturn().getResponse().getStatus();
        Callable<Integer> two = () -> serve(secondOrder, shared).andReturn().getResponse().getStatus();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(one, two));
            assertEquals(List.of(200, 409), List.of(results.get(0).get(), results.get(1).get()).stream().sorted().toList());
        }
        assertEquals(List.of("READY", "SERVED"), List.of(fulfillmentOf(firstOrder), fulfillmentOf(secondOrder))
                .stream().sorted().toList());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE source_event_id = ?",
                Integer.class, shared.commandId()));
    }

    @Test
    void refusesPreparingCancelledAndStaleOrdersWithTheirOwnCodes() throws Exception {
        UUID preparing = placeSaladOrder(A1);
        advanceTo(preparing, "PREPARING");
        serve(preparing, command(TABLE_SESSION_A1, versionOf(preparing))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_READY"));

        UUID cancelled = placeSaladOrder(A1);
        cancelAll(staff(WAITER), cancelled).andExpect(status().isCreated());
        serve(cancelled, command(TABLE_SESSION_A1, versionOf(cancelled))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CANCELLED"));

        UUID ready = readyOrder();
        serve(ready, command(TABLE_SESSION_A1, versionOf(ready) - 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_VERSION_CONFLICT"));
    }

    @Test
    void preservesDeliveryOfRemainingItemsAfterAnEarlierPartialCancellation() throws Exception {
        UUID order = placeMixedOrder(A1);
        cancel(staff(WAITER), order, newKey(), cancelItems(lineOf(order, DevFixtures.SALAD), 1))
                .andExpect(status().isCreated());
        advanceTo(order, "READY");
        serve(order, command(TABLE_SESSION_A1, versionOf(order))).andExpect(status().isOk());
        assertEquals("SERVED", fulfillmentOf(order));
    }

    @Test
    void servingAnAlreadyReadyOrderDoesNotOpenACancellationRace() throws Exception {
        UUID order = readyOrder();
        var command = command(TABLE_SESSION_A1, versionOf(order));
        Callable<Integer> serve = () -> serve(order, command).andReturn().getResponse().getStatus();
        Callable<Integer> cancel = () -> cancelAll(staff(WAITER), order).andReturn().getResponse().getStatus();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(serve, cancel));
            assertEquals(200, results.get(0).get());
            assertEquals(409, results.get(1).get());
        }
        assertEquals("SERVED", fulfillmentOf(order));
    }

    @Test
    void tenantIsolationAndSignedDelegationAreEnforcedBeforeMutation() throws Exception {
        UUID order = readyOrder();
        long version = versionOf(order);
        var command = command(TABLE_SESSION_A1, version);
        String json = toJson(command);
        String target = BASE + order + "/serve";

        mvc.perform(post(target).secure(true).header(HttpHeaders.AUTHORIZATION, "Staff " + WAITER)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
        mvc.perform(signed(post(target), "POST", target, json, WAITER, RESTAURANT_A)
                        .header("Idempotency-Key", command.commandId()).content(json + " "))
                .andExpect(status().isForbidden());
        mvc.perform(signed(post(target), "POST", target, json, WAITER, RESTAURANT_A)
                        .secure(false).header("Idempotency-Key", command.commandId()))
                .andExpect(status().isForbidden());
        mvc.perform(signed(post(target), "POST", target, json, DevFixtures.STAFF_WAITER_B, RESTAURANT_A)
                        .header("Idempotency-Key", command.commandId()))
                .andExpect(status().isForbidden());
        mvc.perform(signed(post(target), "POST", target, json, "inactive-staff-session", RESTAURANT_A)
                        .header("Idempotency-Key", command.commandId()))
                .andExpect(status().isUnauthorized());
        var kitchen = new ServeOrderCommandRequest(command.commandId(), RESTAURANT_A, TABLE_SESSION_A1, version,
                DevFixtures.USER_KITCHEN_A, command.requestedAt());
        String kitchenBody = toJson(kitchen);
        mvc.perform(signed(post(target), "POST", target, kitchenBody, DevFixtures.STAFF_KITCHEN_A, RESTAURANT_A)
                        .header("Idempotency-Key", command.commandId()))
                .andExpect(status().isForbidden());
        UUID foreign = placeSaladOrder(DevFixtures.TOKEN_B1);
        serve(foreign, command).andExpect(status().isNotFound());
        query(order, command.commandId()).andExpect(status().isNotFound());
        assertEquals(version, versionOf(order));
    }

    private UUID readyOrder() throws Exception {
        UUID order = placeSaladOrder(DevFixtures.TOKEN_A1);
        advanceTo(order, "READY");
        return order;
    }

    private static ServeOrderCommandRequest command(UUID tableSession, long version) {
        return new ServeOrderCommandRequest(UUID.randomUUID(), RESTAURANT_A, tableSession, version, USER_WAITER_A,
                Instant.now());
    }

    private ResultActions serve(UUID order, ServeOrderCommandRequest command) throws Exception {
        String target = BASE + order + "/serve";
        return mvc.perform(signed(post(target), "POST", target, toJson(command), WAITER, RESTAURANT_A)
                .header("Idempotency-Key", command.commandId()));
    }

    private ResultActions query(UUID order, UUID command) throws Exception {
        String target = BASE + order + "/serve-commands/" + command + "?restaurantId=" + RESTAURANT_A;
        return mvc.perform(signed(get(URI.create(target)), "GET", target, "", WAITER, RESTAURANT_A));
    }

    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request, String method,
                                                        String target, String body, String staff, UUID restaurant)
            throws Exception {
        Instant now = Instant.now();
        String proof = Jwts.builder().header().keyId("test").and()
                .issuer("restofy-waiter").subject("waiter-service").audience().add("restofy-order").and()
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(30))).claim("token_type", "service")
                .claim("scope", "order:serve").claim("restaurant_id", restaurant.toString())
                .claim("method", method).claim("target", target).claim("body_sha256", digest(body))
                .claim("staff_sha256", digest(staff)).signWith(SIGNER.getPrivate(), Jwts.SIG.RS256).compact();
        return request.secure(true).header(HttpHeaders.AUTHORIZATION, "Service " + proof)
                .header(DELEGATION, staff).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
