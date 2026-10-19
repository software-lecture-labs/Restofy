package com.restofy.menu.support;

import com.restofy.menu.application.service.RatingSummary;
import com.restofy.menu.application.service.RatingSummaryPort;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Test-only stand-in for the Rating service (lives in src/test, never packaged). It records every call, including
 * whether a database transaction was open at that moment, and answers what the test tells it. By default it is
 * "unavailable", like production. It models no network, no timeout and no real Rating contract: none exists yet.
 */
public class TestRatingSummaryPort implements RatingSummaryPort {

    /** One call the menu made to the rating source. */
    public record Call(UUID restaurantId, UUID productId, boolean inTransaction) { }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Call, RatingSummary> behaviour = call -> RatingSummary.unavailable();

    public void reset() {
        calls.clear();
        behaviour = call -> RatingSummary.unavailable();
    }

    public void answer(RatingSummary summary) {
        behaviour = call -> summary;
    }

    public void answerWith(Function<Call, RatingSummary> function) {
        behaviour = function;
    }

    public void failWith(RuntimeException failure) {
        behaviour = call -> {
            throw failure;
        };
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public RatingSummary summaryFor(UUID restaurantId, UUID productId) {
        Call call = new Call(restaurantId, productId, TransactionSynchronizationManager.isActualTransactionActive());
        calls.add(call);
        return behaviour.apply(call);
    }
}
