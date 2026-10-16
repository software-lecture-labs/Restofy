package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Concurrent callers on one order: exactly one change happens, and nothing is written twice. */
class OrderFulfillmentConcurrencyTests extends AbstractOrderStatusApiTest {

    private static final int THREADS = 8;
    private static final String A1 = DevFixtures.TOKEN_A1;

    /** Starts all callables at the same moment and collects their HTTP statuses and bodies. */
    private List<int[]> race(List<Callable<int[]>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<int[]>> futures = new ArrayList<>();
            for (Callable<int[]> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<int[]> results = new ArrayList<>();
            for (Future<int[]> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int[] result(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        var response = actions.andReturn().getResponse();
        String body = response.getContentAsString();
        // second slot: 1 if the call reported a real change / APPLIED, 0 otherwise
        int applied = body.contains("\"changed\":true") || body.contains("\"outcome\":\"APPLIED\"")
                && body.contains("\"duplicate\":false") ? 1 : 0;
        return new int[]{response.getStatus(), applied};
    }

    @Test
    void theSameKitchenEventDeliveredManyTimesAtOnceIsAppliedExactlyOnce() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var body = kitchenBody(UUID.randomUUID(), DevFixtures.RESTAURANT_A, orderId, "PREPARING");
        List<Callable<int[]>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> result(kitchenEvent(service(KITCHEN_CREDENTIAL), body)));
        }
        List<int[]> results = race(calls);

        assertEquals(THREADS, results.stream().filter(r -> r[0] == 200).count(), "every delivery is acknowledged");
        assertEquals(1, results.stream().mapToInt(r -> r[1]).sum(), "exactly one delivery applied it");
        assertEquals("PREPARING", fulfillmentOf(orderId));
        assertEquals(2, fulfillmentHistory(orderId).size());
        assertEquals(1, changeEvents(orderId));
        assertEquals(1, count("inbox_events"));
    }

    @Test
    void sameStaffMemberRepeatingTheSameStepAtOnceChangesItOnce() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "READY");
        long version = versionOf(orderId);
        List<Callable<int[]>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> result(patchStatus(staff(DevFixtures.STAFF_WAITER_A), orderId, "SERVED")));
        }
        List<int[]> results = race(calls);

        assertEquals(THREADS, results.stream().filter(r -> r[0] == 200).count());
        assertEquals(1, results.stream().mapToInt(r -> r[1]).sum(), "one real change, the rest are no-ops");
        assertEquals("SERVED", fulfillmentOf(orderId));
        assertEquals(version + 1, versionOf(orderId));
        assertEquals(List.of("READY>SERVED|STAFF|STAFF_API"),
                fulfillmentHistory(orderId).subList(3, fulfillmentHistory(orderId).size()));
        assertEquals(1, destinationsOfChangeTo(orderId, "SERVED").stream().filter("WAITER"::equals).count());
    }

    @Test
    void twoDifferentWaitersServingTheSameOrderAtOnceOnlyOneWins() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        advanceTo(orderId, "READY");
        String other = staff(registerSecondWaiter());
        String first = staff(DevFixtures.STAFF_WAITER_A);
        List<int[]> results = race(List.of(
                () -> result(patchStatus(first, orderId, "SERVED")),
                () -> result(patchStatus(other, orderId, "SERVED"))));

        assertEquals(1, results.stream().filter(r -> r[0] == 200).count());
        assertEquals(1, results.stream().filter(r -> r[0] == 409).count(), "the loser is told, not silently accepted");
        assertEquals("SERVED", fulfillmentOf(orderId));
        long servedRows = fulfillmentHistory(orderId).stream().filter(h -> h.startsWith("READY>SERVED")).count();
        assertEquals(1, servedRows);
    }

    @Test
    void aStaffChangeAndAKitchenEventRacingForTheSameStepNeverDoubleApply() throws Exception {
        UUID orderId = placeSaladOrder(A1);
        var event = kitchenBody(UUID.randomUUID(), DevFixtures.RESTAURANT_A, orderId, "PREPARING");
        List<int[]> results = race(List.of(
                () -> result(patchStatus(staff(DevFixtures.STAFF_KITCHEN_A), orderId, "PREPARING")),
                () -> result(kitchenEvent(service(KITCHEN_CREDENTIAL), event))));

        // whoever gets the lock first makes the step; the Kitchen service is acknowledged either way, while the
        // staff caller is told (409) if the service already made that step, since it is not the same caller
        assertEquals(2, results.stream().filter(r -> r[0] == 200 || r[0] == 409).count());
        assertEquals(true, results.stream().anyMatch(r -> r[0] == 200 && r[1] == 1), "one of them applied it");
        assertEquals("PREPARING", fulfillmentOf(orderId));
        long preparingRows = fulfillmentHistory(orderId).stream().filter(h -> h.startsWith("RECEIVED>PREPARING"))
                .count();
        assertEquals(1, preparingRows, "the step happened exactly once, whoever got there first");
        assertEquals(1, changeEvents(orderId));
    }
}
