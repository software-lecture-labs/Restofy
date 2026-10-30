package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import com.restofy.order.infrastructure.client.mock.InMemoryServiceIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-QR-001 (Order Service side): the QR Service asks whether a table session may be closed, the Order Service looks at
 * ALL orders of the session and, when eligible, refuses new orders for it until the QR Service confirms.
 */
class TableSessionClosureApiTests extends AbstractOrderStatusApiTest {

    private static final UUID SESSION = DevFixtures.TABLE_SESSION_A1;
    private static final UUID RESTAURANT = DevFixtures.RESTAURANT_A;
    private static final String QR = service(InMemoryServiceIdentityAdapter.QR_CREDENTIAL);

    private static String preparations(UUID session) {
        return "/api/v1/internal/table-sessions/" + session + "/closure-preparations";
    }

    private ResultActions prepare(String authorization, UUID session, Object body) throws Exception {
        var request = post(preparations(session)).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    private ResultActions prepare(UUID operation) throws Exception {
        return prepare(QR, SESSION, Map.of("restaurantId", RESTAURANT, "operationId", operation));
    }

    private ResultActions lookup(String authorization, UUID session, UUID operation, UUID restaurant) throws Exception {
        var request = get(preparations(session) + "/" + operation).param("restaurantId", restaurant.toString());
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    private ResultActions confirm(String authorization, UUID session, UUID operation, UUID restaurant, String outcome)
            throws Exception {
        var request = post(preparations(session) + "/" + operation + "/confirmation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("restaurantId", restaurant, "outcome", outcome)));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    private ResultActions confirm(UUID operation, String outcome) throws Exception {
        return confirm(QR, SESSION, operation, RESTAURANT, outcome);
    }

    /** A guest orders a salad; the kitchen and the waiter finish it and the payment service collects the money. */
    private UUID finishedAndPaidOrder(String guestToken) throws Exception {
        UUID orderId = placeSaladOrder(guestToken);
        advanceTo(orderId, "COMPLETED");
        pay(orderId, "PENDING", 30).andExpect(status().isOk());
        pay(orderId, "PAID", 20).andExpect(status().isOk());
        return orderId;
    }

    private List<String> blockersOf(ResultActions result) throws Exception {
        List<String> blockers = new ArrayList<>();
        body(result).path("blockers").forEach(b -> blockers.add(b.asString()));
        return blockers;
    }

    private int closures() {
        return count("table_session_closures");
    }

    private ResultActions orderAgain(String guestToken) throws Exception {
        addItem(guestToken, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        return placeOrder(guestToken, newKey(), confirmedCheckout(guestToken));
    }

    // ---- who may ask ------------------------------------------------------------------------------------------------

    @Test
    void onlyTheQrServiceMayUseTheClosureEndpoints() throws Exception {
        UUID operation = UUID.randomUUID();
        Map<String, Object> body = Map.of("restaurantId", RESTAURANT, "operationId", operation);

        prepare(null, SESSION, body).andExpect(status().isUnauthorized());
        prepare(service("not-a-known-credential"), SESSION, body).andExpect(status().isUnauthorized());
        prepare(guest(DevFixtures.TOKEN_A1), SESSION, body).andExpect(status().isForbidden());
        prepare(staff(DevFixtures.STAFF_ADMIN_A), SESSION, body).andExpect(status().isForbidden());
        prepare(service(KITCHEN_CREDENTIAL), SESSION, body).andExpect(status().isForbidden());
        prepare(service(PAYMENT_CREDENTIAL), SESSION, body).andExpect(status().isForbidden());

        lookup(guest(DevFixtures.TOKEN_A1), SESSION, operation, RESTAURANT).andExpect(status().isForbidden());
        lookup(service(KITCHEN_CREDENTIAL), SESSION, operation, RESTAURANT).andExpect(status().isForbidden());
        confirm(staff(DevFixtures.STAFF_ADMIN_A), SESSION, operation, RESTAURANT, "CLOSED")
                .andExpect(status().isForbidden());
        confirm(service(PAYMENT_CREDENTIAL), SESSION, operation, RESTAURANT, "CLOSED").andExpect(status().isForbidden());

        assertEquals(0, closures());
    }

    @Test
    void theQrServiceCredentialOpensNothingElse() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);

        getWith(QR, "/api/v1/orders/" + orderId).andExpect(status().isForbidden());
        getWith(QR, "/api/v1/carts/current").andExpect(status().isForbidden());
        patchStatus(QR, orderId, "PREPARING").andExpect(status().isForbidden());
        kitchenEvent(QR, kitchenBody(UUID.randomUUID(), RESTAURANT, orderId, "PREPARING"))
                .andExpect(status().isForbidden());
        paymentEvent(QR, paymentBody(UUID.randomUUID(), orderId, "PENDING", SALAD_DUE, "TRY",
                java.time.Instant.now())).andExpect(status().isForbidden());
    }

    @Test
    void aBodyWithoutAnOperationOrRestaurantIsRefused() throws Exception {
        prepare(QR, SESSION, Map.of("restaurantId", RESTAURANT)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        prepare(QR, SESSION, Map.of("operationId", UUID.randomUUID())).andExpect(status().isUnprocessableEntity());
        confirm(QR, SESSION, UUID.randomUUID(), RESTAURANT, "MAYBE").andExpect(status().is4xxClientError());

        assertEquals(0, closures());
    }

    // ---- eligibility (K2, K3, K7) -----------------------------------------------------------------------------------

    @Test
    void aTableSessionWithoutOrdersIsNotEligibleAndNothingIsHeld() throws Exception {
        prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.outcome").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.blockers[0]").value("NO_ORDERS"))
                .andExpect(jsonPath("$.orderCount").value(0));

        assertEquals(0, closures());
    }

    @Test
    void finishedAndPaidOrdersPrepareTheClosure() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();

        JsonNode prepared = body(prepare(operation).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.outcome").value("ELIGIBLE"))
                .andExpect(jsonPath("$.operationId").value(operation.toString()))
                .andExpect(jsonPath("$.restaurantId").value(RESTAURANT.toString()))
                .andExpect(jsonPath("$.tableSessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.blockers").isEmpty()));

        assertEquals(64, prepared.path("scopeFingerprint").asString().length());
        assertEquals(1, closures());
        assertEquals("PREPARED", jdbc.queryForObject("SELECT status FROM table_session_closures", String.class));
    }

    @Test
    void paidButNotYetServedKeepsTheTableOpen() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        pay(orderId, "PENDING", 30).andExpect(status().isOk());
        pay(orderId, "PAID", 20).andExpect(status().isOk());

        var result = prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.outcome").value("NOT_ELIGIBLE"));

        assertEquals(List.of("ORDER_NOT_COMPLETED"), blockersOf(result));
        assertEquals(0, closures());
    }

    @Test
    void servedButNotPaidKeepsTheTableOpen() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        advanceTo(orderId, "COMPLETED");

        var result = prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_ELIGIBLE"));

        assertEquals(List.of("PAYMENT_NOT_SETTLED"), blockersOf(result));
        assertEquals(0, closures());
    }

    @Test
    void aPaymentStillInFlightIsUnknownAndHoldsNothing() throws Exception {
        UUID orderId = placeSaladOrder(DevFixtures.TOKEN_A1);
        advanceTo(orderId, "COMPLETED");
        pay(orderId, "PENDING", 30).andExpect(status().isOk());

        var result = prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.outcome").value("UNKNOWN"));

        assertEquals(List.of("PAYMENT_IN_PROGRESS"), blockersOf(result));
        assertEquals(0, closures());
        // nothing is held, so the table still takes orders
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());
    }

    @Test
    void theOrdersOfEveryGuestOfTheSessionCount() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID second = placeSaladOrder(DevFixtures.TOKEN_A2);

        var result = prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.orderCount").value(2));
        assertEquals(List.of("ORDER_NOT_COMPLETED", "PAYMENT_NOT_SETTLED"), blockersOf(result));

        advanceTo(second, "COMPLETED");
        pay(second, "PENDING", 30).andExpect(status().isOk());
        pay(second, "PAID", 20).andExpect(status().isOk());
        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.orderCount").value(2));
    }

    @Test
    void aGuestWhoOrderedNothingOwesNothing() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        // guest 2 only looks at the menu and fills a cart
        addItem(DevFixtures.TOKEN_A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());

        prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.outcome").value("ELIGIBLE"))
                .andExpect(jsonPath("$.orderCount").value(1));
    }

    @Test
    void aCancelledOrderOwesNothingButAnotherUnpaidOneStillBlocks() throws Exception {
        UUID cancelled = placeSaladOrder(DevFixtures.TOKEN_A1);
        mvc.perform(post("/api/v1/orders/" + cancelled + "/cancellations").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, guest(DevFixtures.TOKEN_A1)).header("Idempotency-Key", newKey())
                .content(toJson(Map.of("cancelAll", true)))).andExpect(status().isCreated());
        finishedAndPaidOrder(DevFixtures.TOKEN_A2);

        prepare(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARED"))
                .andExpect(jsonPath("$.orderCount").value(2));
    }

    @Test
    void theOrdersOfAnotherRestaurantAreNeverLookedAt() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);

        // asking about the same session id under another restaurant sees no orders and holds nothing of restaurant A
        prepare(QR, SESSION, Map.of("restaurantId", DevFixtures.RESTAURANT_B, "operationId", UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.blockers[0]").value("NO_ORDERS"));
        assertEquals(0, closures());
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());
    }

    // ---- K8: checkout and the closure are ordered ---------------------------------------------------------------------

    @Test
    void oncePreparedANewOrderForTheSessionIsRefusedAndNothingIsWritten() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("PREPARED"));

        addItem(DevFixtures.TOKEN_A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        int orders = count("orders");
        int idempotency = count("idempotency_records");
        int events = count("outbox_events");
        int history = count("order_status_history");
        long counter = jdbc.queryForObject("SELECT last_value FROM order_number_counters", Long.class);

        placeOrder(DevFixtures.TOKEN_A2, newKey(), confirmedCheckout(DevFixtures.TOKEN_A2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));

        assertEquals(orders, count("orders"));
        assertEquals(idempotency, count("idempotency_records"), "a refused order claims no idempotency key");
        assertEquals(events, count("outbox_events"));
        assertEquals(history, count("order_status_history"));
        assertEquals(counter, jdbc.queryForObject("SELECT last_value FROM order_number_counters", Long.class),
                "a refused order does not use an order number");
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts WHERE guest_session_id = ?", String.class,
                DevFixtures.GUEST_A2), "the guest's cart is untouched");
    }

    @Test
    void anOrderThatWasPlacedBeforeThePreparationIsPartOfItsScope() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        // a second order is accepted before anybody asked to close
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isCreated());

        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.orderCount").value(2));
    }

    @Test
    void otherTableSessionsAreNotAffectedByAPreparedClosure() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("PREPARED"));

        orderAgain(DevFixtures.TOKEN_B1).andExpect(status().isCreated());
    }

    // ---- K9: one durable operation ------------------------------------------------------------------------------------

    @Test
    void askingAgainWithTheSameOperationReturnsTheSameClosureAndPreparesNothingNew() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();

        String first = prepare(operation).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = prepare(operation).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(json.readTree(first), json.readTree(second));
        assertEquals(1, closures());
    }

    @Test
    void aSecondOperationForAPreparedSessionIsRefused() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID first = UUID.randomUUID();
        prepare(first).andExpect(jsonPath("$.state").value("PREPARED"));

        prepare(UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_IN_PROGRESS"))
                .andExpect(jsonPath("$.details.operationId").value(first.toString()));
        assertEquals(1, closures());
    }

    @Test
    void anOperationIdCannotBeReusedForAnotherTableSession() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));

        prepare(QR, DevFixtures.TABLE_SESSION_B1, Map.of("restaurantId", RESTAURANT, "operationId", operation))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        prepare(QR, SESSION, Map.of("restaurantId", DevFixtures.RESTAURANT_B, "operationId", operation))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aQrServiceThatLostTheResponseCanAskWhatBecameOfTheOperation() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        String prepared = prepare(operation).andReturn().getResponse().getContentAsString();

        String looked = lookup(QR, SESSION, operation, RESTAURANT).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(prepared), json.readTree(looked));

        lookup(QR, SESSION, UUID.randomUUID(), RESTAURANT).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOSURE_NOT_FOUND"));
        lookup(QR, DevFixtures.TABLE_SESSION_B1, operation, RESTAURANT).andExpect(status().isNotFound());
        lookup(QR, SESSION, operation, DevFixtures.RESTAURANT_B).andExpect(status().isNotFound());
    }

    @Test
    void aPreparedClosureNeverExpiresByItself() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        // a long time passes: nothing releases it, a timeout does not prove the closing failed
        jdbc.execute("ALTER TABLE table_session_closures DISABLE TRIGGER trg_table_session_closures_guard");
        try {
            jdbc.update("UPDATE table_session_closures SET prepared_at = prepared_at - interval '30 days'");
        } finally {
            jdbc.execute("ALTER TABLE table_session_closures ENABLE TRIGGER trg_table_session_closures_guard");
        }

        lookup(QR, SESSION, operation, RESTAURANT).andExpect(jsonPath("$.state").value("PREPARED"));
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));
    }

    // ---- the QR Service reports the result ------------------------------------------------------------------------------

    @Test
    void closedCompletesTheClosureAndOrdersStayRefused() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        confirm(operation, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        confirm(operation, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));

        confirm(operation, "NOT_CLOSED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));
        // a closed session cannot be prepared again either
        prepare(UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_IN_PROGRESS"));
    }

    @Test
    void notClosedReleasesTheHoldAndOrdersAreAcceptedAgain() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);
        addItem(DevFixtures.TOKEN_A2, product(DevFixtures.SALAD, 1)).andExpect(status().isOk());
        placeOrder(DevFixtures.TOKEN_A2, newKey(), confirmedCheckout(DevFixtures.TOKEN_A2))
                .andExpect(status().isConflict());

        confirm(operation, "NOT_CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.releasedAt").isNotEmpty());
        confirm(operation, "NOT_CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"));
        confirm(operation, "CLOSED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        placeOrder(DevFixtures.TOKEN_A2, newKey(), confirmedCheckout(DevFixtures.TOKEN_A2))
                .andExpect(status().isCreated());
        // and a later, new operation is possible once the new order is finished and paid
        prepare(UUID.randomUUID()).andExpect(jsonPath("$.state").value("NOT_PREPARED"));
    }

    @Test
    void closedIsRefusedWhenTheOrdersChangedAfterThePreparationAndTheHoldStays() throws Exception {
        UUID orderId = finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation).andExpect(jsonPath("$.state").value("PREPARED"));
        // something about the money changed in between (here: the order owes money again)
        jdbc.update("UPDATE orders SET current_due_minor = 100 WHERE id = ?", orderId);

        confirm(operation, "CLOSED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_SCOPE_CHANGED"))
                .andExpect(jsonPath("$.details.outcome").value("NOT_ELIGIBLE"));

        lookup(QR, SESSION, operation, RESTAURANT).andExpect(jsonPath("$.state").value("PREPARED"));
        orderAgain(DevFixtures.TOKEN_A2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_CLOSING"));
        // the QR Service can still say it did not close, which releases the hold
        confirm(operation, "NOT_CLOSED").andExpect(jsonPath("$.state").value("RELEASED"));
    }

    @Test
    void confirmingAnUnknownOrForeignOperationIsNotFound() throws Exception {
        finishedAndPaidOrder(DevFixtures.TOKEN_A1);
        UUID operation = UUID.randomUUID();
        prepare(operation);

        confirm(UUID.randomUUID(), "CLOSED").andExpect(status().isNotFound());
        confirm(QR, DevFixtures.TABLE_SESSION_B1, operation, RESTAURANT, "CLOSED").andExpect(status().isNotFound());
        confirm(QR, SESSION, operation, DevFixtures.RESTAURANT_B, "CLOSED").andExpect(status().isNotFound());
        lookup(QR, SESSION, operation, RESTAURANT).andExpect(jsonPath("$.state").value("PREPARED"));
    }
}
