package com.restofy.order.api.controller;

import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 3 (a waiter gives the reason afterwards) and reading the cancellations of an order. */
class OrderCancellationReasonCompletionApiTests extends AbstractCancellationApiTest {

    private static final String WAITER = staff(DevFixtures.STAFF_WAITER_A);
    private static final String ADMIN = staff(DevFixtures.STAFF_ADMIN_A);
    private static final String KITCHEN = staff(DevFixtures.STAFF_KITCHEN_A);

    private org.springframework.test.web.servlet.ResultActions completeReason(String authorization, UUID orderId,
                                                                               UUID cancellationId, Object body)
            throws Exception {
        var request = patch("/api/v1/orders/" + orderId + "/cancellations/" + cancellationId + "/reason")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(toJson(body));
        if (authorization != null) {
            request.header(org.springframework.http.HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    private UUID cancelledWithoutReason(UUID orderId) throws Exception {
        return requestIdOf(cancelAll(guest(A1), orderId).andExpect(status().isCreated()));
    }

    // ---- the waiter's completion ------------------------------------------------------------------------------------

    @Test
    void aWaiterGivesTheMissingReasonAfterwardsAndItIsAudited() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].reasonPending").value(true));

        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("WAIT_TOO_LONG"),
                "reasonText", "Table 4 left")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonPending").value(false))
                .andExpect(jsonPath("$.reasons[*].source", contains("STAFF", "STAFF")))
                .andExpect(jsonPath("$.reasons[0].code").value("WAIT_TOO_LONG"))
                .andExpect(jsonPath("$.reasons[1].text").value("Table 4 left"))
                .andExpect(jsonPath("$.reasons[0].addedBy.role").value("WAITER"))
                .andExpect(jsonPath("$.reasons[0].addedBy.id").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap("SELECT staff_reason_added_by, staff_reason_added_at, version"
                + " FROM order_cancellations WHERE id = ?", cancellationId);
        assertEquals(DevFixtures.USER_WAITER_A.toString(), row.get("staff_reason_added_by"));
        assertEquals(true, row.get("staff_reason_added_at") != null);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_cancellation_reasons WHERE added_by_type = 'STAFF'"
                + " AND added_by_id = ? AND added_by_role = 'WAITER'", Integer.class,
                DevFixtures.USER_WAITER_A.toString()));
        // the guest sees it too
        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations")
                .andExpect(jsonPath("$.cancellations[0].reasonPending").value(false))
                .andExpect(jsonPath("$.cancellations[0].reasons.length()").value(2));
    }

    @Test
    void theReasonCanOnlyBeGivenOnceAndNeverOverwritten() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        var first = Map.of("reasonCodes", List.of("ITEM_UNAVAILABLE"));
        completeReason(WAITER, orderId, cancellationId, first).andExpect(status().isOk());

        // the very same completion again is a harmless retry
        completeReason(WAITER, orderId, cancellationId, first).andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons.length()").value(1));
        // anything else is refused: no overwrite, no addition, by the same or another waiter
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("OTHER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_REASON_ALREADY_SET"));
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("ITEM_UNAVAILABLE"),
                "reasonText", "more")).andExpect(status().isConflict());
        completeReason(staff(registerSecondWaiter()), orderId, cancellationId, first)
                .andExpect(status().isConflict());

        assertEquals(1, count("order_cancellation_reasons"));
        assertEquals(DevFixtures.USER_WAITER_A.toString(), jdbc.queryForObject(
                "SELECT staff_reason_added_by FROM order_cancellations WHERE id = ?", String.class, cancellationId));
    }

    @Test
    void aWaiterCannotAddToAReasonTheCustomerGave() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = requestIdOf(cancel(guest(A1), orderId, newKey(),
                cancelAllWith(List.of("CHANGED_MIND"), null)).andExpect(status().isCreated()));

        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "my own idea"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_REASON_ALREADY_SET"));
        assertEquals(1, count("order_cancellation_reasons"));
        assertEquals(null, jdbc.queryForObject("SELECT staff_reason_added_at FROM order_cancellations WHERE id = ?",
                java.sql.Timestamp.class, cancellationId));
    }

    @Test
    void theReasonCanBeCompletedForARequestStillWaitingAndForARejectedOne() throws Exception {
        UUID waitingOrder = placeMixedOrder(A1);
        advanceTo(waitingOrder, "PREPARING");
        UUID waiting = requestIdOf(cancelAll(guest(A1), waitingOrder).andExpect(status().isCreated()));
        completeReason(WAITER, waitingOrder, waiting, Map.of("reasonCodes", List.of("KITCHEN_ERROR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"));
        kitchenDecides(waitingOrder, waiting, "REJECTED", "KITCHEN_DECLINED").andExpect(status().isOk());
        assertEquals("REJECTED", cancellationStatus(waiting));
        completeReason(WAITER, waitingOrder, waiting, Map.of("reasonCodes", List.of("KITCHEN_ERROR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        // the reason of a REJECTED request given later never changes the decision
        UUID second = placeMixedOrder(DevFixtures.TOKEN_A2);
        advanceTo(second, "PREPARING");
        UUID other = requestIdOf(cancelAll(guest(DevFixtures.TOKEN_A2), second).andExpect(status().isCreated()));
        kitchenDecides(second, other, "REJECTED", "ALREADY_COOKED").andExpect(status().isOk());
        completeReason(WAITER, second, other, Map.of("reasonText", "customer insisted")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.code").value("ALREADY_COOKED"));
    }

    @Test
    void anEmptyOrInvalidCompletionIsRefusedAndWritesNothing() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);

        completeReason(WAITER, orderId, cancellationId, Map.of()).andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "   ")).andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("NOPE")))
                .andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonCodes", List.of("OTHER", "OTHER")))
                .andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "x".repeat(301)))
                .andExpect(status().isUnprocessableContent());
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "bell\u0007"))
                .andExpect(status().isUnprocessableContent());

        assertEquals(0, count("order_cancellation_reasons"));
        completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "fine now")).andExpect(status().isOk());
    }

    @Test
    void onlyAWaiterOfTheRestaurantMayCompleteTheReason() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        var body = Map.of("reasonText", "late reason");

        completeReason(null, orderId, cancellationId, body).andExpect(status().isUnauthorized());
        completeReason(guest(A1), orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(ADMIN, orderId, cancellationId, body).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        completeReason(KITCHEN, orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(service(KITCHEN_CREDENTIAL), orderId, cancellationId, body).andExpect(status().isForbidden());
        completeReason(staff(DevFixtures.STAFF_WAITER_B), orderId, cancellationId, body).andExpect(status().isNotFound());
        completeReason(WAITER, UUID.randomUUID(), cancellationId, body).andExpect(status().isNotFound());
        completeReason(WAITER, orderId, UUID.randomUUID(), body).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_FOUND"));
        // a cancellation of another order of the same restaurant is not reachable through this order
        UUID otherOrder = placeSaladOrder(DevFixtures.TOKEN_A2);
        completeReason(WAITER, otherOrder, cancellationId, body).andExpect(status().isNotFound());

        assertEquals(0, count("order_cancellation_reasons"));
    }

    @Test
    void twoWaitersCompletingTheReasonAtOnceLeaveExactlyOne() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        UUID cancellationId = cancelledWithoutReason(orderId);
        String second = staff(registerSecondWaiter());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var one = pool.submit(() -> {
                start.await();
                return completeReason(WAITER, orderId, cancellationId, Map.of("reasonText", "from the first waiter"))
                        .andReturn().getResponse().getStatus();
            });
            var two = pool.submit(() -> {
                start.await();
                return completeReason(second, orderId, cancellationId, Map.of("reasonText", "from the second waiter"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>(List.of(one.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    two.get(30, java.util.concurrent.TimeUnit.SECONDS)));
            statuses.sort(Integer::compare);
            assertEquals(List.of(200, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("order_cancellation_reasons"));
    }

    // ---- reading the cancellations ----------------------------------------------------------------------------------

    @Test
    void theCancellationsOfAnOrderAreListedOldestFirstForTheOwnerAndStaff() throws Exception {
        UUID orderId = placeMixedOrder(A1);
        cancel(guest(A1), orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.SALAD), 1))
                .andExpect(status().isCreated());
        cancel(WAITER, orderId, newKey(), cancelItems(lineOf(orderId, DevFixtures.BURGER), 1))
                .andExpect(status().isCreated());

        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.cancellations.length()").value(2))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.type").value("GUEST"))
                .andExpect(jsonPath("$.cancellations[1].requestedBy.type").value("STAFF"))
                .andExpect(jsonPath("$.cancellations[1].requestedBy.id").doesNotExist());
        getWith(WAITER, "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellations[1].requestedBy.id").doesNotExist());
        getWith(ADMIN, "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellations[1].requestedBy.id").value(DevFixtures.USER_WAITER_A.toString()))
                .andExpect(jsonPath("$.cancellations[0].requestedBy.id").doesNotExist());
    }

    @Test
    void aOrderWithoutCancellationsHasAnEmptyList() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        getWith(guest(A1), "/api/v1/orders/" + orderId + "/cancellations").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellations.length()").value(0));
    }

    @Test
    void theListIsClosedToOthers() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        cancelledWithoutReason(orderId);
        String path = "/api/v1/orders/" + orderId + "/cancellations";

        getWith(null, path).andExpect(status().isUnauthorized());
        getWith(guest(DevFixtures.TOKEN_A2), path).andExpect(status().isNotFound());
        getWith(guest(DevFixtures.TOKEN_B1), path).andExpect(status().isNotFound());
        getWith(staff(DevFixtures.STAFF_WAITER_B), path).andExpect(status().isNotFound());
        getWith(KITCHEN, path).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        getWith(service(KITCHEN_CREDENTIAL), path).andExpect(status().isForbidden());
        getWith(WAITER, "/api/v1/orders/" + UUID.randomUUID() + "/cancellations").andExpect(status().isNotFound());

        qr.register(A1, DevFixtures.guestA1(SessionState.CLOSED));
        getWith(guest(A1), path).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TABLE_SESSION_NOT_ACTIVE"));
        getWith(WAITER, path).andExpect(status().isOk());
    }
}
