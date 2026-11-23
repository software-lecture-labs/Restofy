package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.client.mock.InMemoryServiceIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WAITER-01: the table list asks how many orders are still open for many table sessions in one request. Staff only,
 * the restaurant comes from the verified identity, "active" is the same set as the list's {@code active} view.
 */
class OrderSessionSummariesApiTests extends AbstractCartApiTest {

    private static final String SUMMARIES = "/api/v1/orders/session-summaries";
    private static final String ADMIN_A = staff(DevFixtures.STAFF_ADMIN_A);
    private static final String WAITER_A = staff(DevFixtures.STAFF_WAITER_A);

    private final AtomicInteger numbers = new AtomicInteger(9000);

    private ResultActions postBody(String authorization, Object body) throws Exception {
        return mvc.perform(withCredential(post(SUMMARIES), authorization)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private ResultActions summaries(String authorization, List<UUID> sessions) throws Exception {
        return postBody(authorization, Map.of("tableSessionIds", sessions));
    }

    private JsonNode counts(String authorization, List<UUID> sessions) throws Exception {
        return json.readTree(summaries(authorization, sessions).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Inserts an order row directly, so a test chooses the session, the status and the payment precisely. */
    private void insertOrder(UUID restaurant, UUID tableSession, String fulfillment, String payment) {
        insertOrder(restaurant, UUID.randomUUID(), tableSession, fulfillment, payment);
    }

    private void insertOrder(UUID restaurant, UUID table, UUID tableSession, String fulfillment, String payment) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO orders (id, restaurant_id, order_no, cart_id, table_id, table_session_id,"
                        + " guest_session_id, fulfillment_status, payment_status, currency, initial_net_minor,"
                        + " initial_vat_minor, initial_gross_minor, current_due_minor, placed_at, version, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'TRY', 10, 0, 10, 10, ?, 0, ?, ?)",
                UUID.randomUUID(), restaurant, "SUM-" + numbers.incrementAndGet(), UUID.randomUUID(),
                table, tableSession, UUID.randomUUID(), fulfillment, payment, Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
    }

    private void insertA(UUID tableSession, String fulfillment) {
        insertOrder(DevFixtures.RESTAURANT_A, tableSession, fulfillment, "NOT_REQUESTED");
    }

    private static Map<UUID, Long> asMap(JsonNode response) {
        Map<UUID, Long> result = new java.util.LinkedHashMap<>();
        response.path("items").forEach(item -> result.put(UUID.fromString(item.path("tableSessionId").asString()),
                item.path("activeOrderCount").asLong()));
        return result;
    }

    // ---- what counts ---------------------------------------------------------------------------------------------

    @Test
    void countsTheActiveStatusesOnlyAndLeavesOutSessionsWithoutActiveOrders() throws Exception {
        UUID busy = UUID.randomUUID();
        UUID single = UUID.randomUUID();
        UUID finished = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        for (String active : List.of("RECEIVED", "PREPARING", "READY", "SERVED")) {
            insertA(busy, active);
        }
        insertA(busy, "COMPLETED");
        insertA(busy, "CANCELLED");
        insertA(single, "READY");
        insertA(finished, "COMPLETED");
        insertA(finished, "CANCELLED");

        JsonNode answer = counts(ADMIN_A, List.of(finished, busy, unknown, single));

        Map<UUID, Long> byId = asMap(answer);
        assertEquals(2, byId.size(), "a session without an active order is absent: absence means zero");
        assertEquals(4L, byId.get(busy), "RECEIVED, PREPARING, READY and SERVED count; COMPLETED and CANCELLED do not");
        assertEquals(1L, byId.get(single));
        assertEquals(List.of(busy, single), new ArrayList<>(byId.keySet()), "answered in the order the sessions were asked");
    }

    @Test
    void paymentIsAnIndependentAxisAndDoesNotChangeTheCount() throws Exception {
        UUID session = UUID.randomUUID();
        insertOrder(DevFixtures.RESTAURANT_A, session, "READY", "NOT_REQUESTED");
        insertOrder(DevFixtures.RESTAURANT_A, session, "SERVED", "PAID");
        insertOrder(DevFixtures.RESTAURANT_A, session, "RECEIVED", "PENDING");
        insertOrder(DevFixtures.RESTAURANT_A, session, "PREPARING", "FAILED");

        assertEquals(Map.of(session, 4L), asMap(counts(WAITER_A, List.of(session))));
    }

    @Test
    void twoSessionsOfTheSameTableNeverMix() throws Exception {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        insertOrder(DevFixtures.RESTAURANT_A, table, earlier, "SERVED", "NOT_REQUESTED");
        insertOrder(DevFixtures.RESTAURANT_A, table, later, "READY", "NOT_REQUESTED");
        insertOrder(DevFixtures.RESTAURANT_A, table, later, "RECEIVED", "NOT_REQUESTED");

        Map<UUID, Long> byId = asMap(counts(ADMIN_A, List.of(earlier, later)));

        assertEquals(1L, byId.get(earlier));
        assertEquals(2L, byId.get(later));
    }

    @Test
    void aRepeatedSessionIdIsCountedOnce() throws Exception {
        UUID session = UUID.randomUUID();
        insertA(session, "READY");
        insertA(session, "SERVED");

        JsonNode answer = counts(ADMIN_A, List.of(session, session, session));

        assertEquals(1, answer.path("items").size());
        assertEquals(2L, answer.path("items").get(0).path("activeOrderCount").asLong());
    }

    // ---- tenant isolation ----------------------------------------------------------------------------------------

    @Test
    void anotherRestaurantsSessionIsNeverCountedAndLooksLikeAnEmptyOne() throws Exception {
        UUID foreign = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        insertOrder(DevFixtures.RESTAURANT_B, foreign, "READY", "NOT_REQUESTED");
        insertOrder(DevFixtures.RESTAURANT_B, foreign, "SERVED", "NOT_REQUESTED");
        insertA(mine, "READY");

        Map<UUID, Long> asA = asMap(counts(ADMIN_A, List.of(foreign, mine)));
        Map<UUID, Long> asB = asMap(counts(staff(DevFixtures.STAFF_ADMIN_B), List.of(foreign, mine)));

        assertEquals(Map.of(mine, 1L), asA, "restaurant A neither sees nor learns that the foreign session exists");
        assertEquals(Map.of(foreign, 2L), asB, "restaurant B sees only its own");
    }

    @Test
    void aRestaurantIdInTheBodyIsRejectedNeverTrusted() throws Exception {
        UUID foreign = UUID.randomUUID();
        insertOrder(DevFixtures.RESTAURANT_B, foreign, "READY", "NOT_REQUESTED");

        // the restaurant is the caller's own and cannot be chosen: an unknown body field is a 400, not a scope
        postBody(ADMIN_A, Map.of("tableSessionIds", List.of(foreign), "restaurantId", DevFixtures.RESTAURANT_B))
                .andExpect(status().isBadRequest());
    }

    // ---- limits --------------------------------------------------------------------------------------------------

    @Test
    void fiveHundredSessionsAreCountedInOneRequestAndFiveHundredAndOneAreRejected() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ids.add(UUID.randomUUID());
        }
        insertA(ids.get(499), "READY");

        assertEquals(Map.of(ids.get(499), 1L), asMap(counts(ADMIN_A, ids)));

        ids.add(UUID.randomUUID());
        summaries(ADMIN_A, ids).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anEmptyOrMissingOrNullContainingListIsInvalid() throws Exception {
        postBody(ADMIN_A, Map.of("tableSessionIds", List.of())).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postBody(ADMIN_A, Map.of()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        List<UUID> withNull = new ArrayList<>();
        withNull.add(UUID.randomUUID());
        withNull.add(null);
        summaries(ADMIN_A, withNull).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void aMalformedSessionIdIsABadRequest() throws Exception {
        postBody(ADMIN_A, Map.of("tableSessionIds", List.of("not-a-uuid"))).andExpect(status().isBadRequest());
    }

    // ---- who may call it -----------------------------------------------------------------------------------------

    @Test
    void adminAndWaiterMayCallItButKitchenMayNot() throws Exception {
        UUID session = UUID.randomUUID();
        insertA(session, "READY");

        assertEquals(Map.of(session, 1L), asMap(counts(ADMIN_A, List.of(session))));
        assertEquals(Map.of(session, 1L), asMap(counts(WAITER_A, List.of(session))));
        summaries(staff(DevFixtures.STAFF_KITCHEN_A), List.of(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void anAnonymousCallerAGuestAndAServiceCannotCallIt() throws Exception {
        UUID session = UUID.randomUUID();
        insertA(session, "READY");

        summaries(null, List.of(session)).andExpect(status().isUnauthorized());
        summaries(staff("unknown-credential"), List.of(session)).andExpect(status().isUnauthorized());
        // a guest cookie is not a staff credential: this is not a guest route, so it stays anonymous
        summaries(guest(DevFixtures.TOKEN_A1), List.of(session)).andExpect(status().isUnauthorized());
        summaries(service(InMemoryServiceIdentityAdapter.KITCHEN_CREDENTIAL), List.of(session))
                .andExpect(status().isForbidden());
    }
}
