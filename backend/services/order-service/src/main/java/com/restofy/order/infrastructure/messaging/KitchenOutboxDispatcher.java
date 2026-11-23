package com.restofy.order.infrastructure.messaging;

import com.restofy.order.infrastructure.client.KitchenEventsHttpClient;
import com.restofy.order.infrastructure.config.KitchenRestaurantScope;
import com.restofy.order.infrastructure.config.RestClientProperties;
import com.restofy.order.infrastructure.persistence.KitchenOutboxDeliveryStore;
import com.restofy.order.infrastructure.persistence.KitchenOutboxDeliveryStore.Attempt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Slf4j
@Component
@Profile("order-kitchen")
public class KitchenOutboxDispatcher {

    static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 8;
    private static final int TRANSPORT_UNAVAILABLE = 0;

    private final KitchenOutboxDeliveryStore store;
    private final KitchenEventsHttpClient kitchen;
    private final KitchenEventProjection projection;
    private final KitchenRestaurantScope scope;
    private final Duration lease;

    KitchenOutboxDispatcher(KitchenOutboxDeliveryStore store, KitchenEventsHttpClient kitchen,
                            KitchenEventProjection projection, KitchenRestaurantScope scope,
                            RestClientProperties timeouts) {
        this.store = store;
        this.kitchen = kitchen;
        this.projection = projection;
        this.scope = scope;
        this.lease = Duration.ofMillis((long) timeouts.connectTimeoutMs() + timeouts.readTimeoutMs()).plusSeconds(5);
    }

    @Scheduled(fixedDelayString = "PT2S")
    @Transactional(propagation = Propagation.NEVER)
    public void dispatch() {
        for (var restaurant : scope.restaurants()) {
            store.expireExhausted(restaurant, MAX_ATTEMPTS, BATCH_SIZE);
            for (int i = 0; i < BATCH_SIZE; i++) {
                var attempt = store.claim(restaurant, MAX_ATTEMPTS, lease);
                if (attempt.isEmpty()) {
                    break;
                }
                deliver(attempt.get());
            }
        }
    }

    private void deliver(Attempt attempt) {
        byte[] body;
        try {
            body = projection.project(attempt);
        } catch (RuntimeException ex) {
            completeFailure(attempt, "KITCHEN_INVALID_OUTBOX_EVENT", null);
            return;
        }
        int status = send(attempt, body);
        if (status == 204) {
            store.complete(attempt, null, null);
            return;
        }
        String code = status == TRANSPORT_UNAVAILABLE ? "KITCHEN_TRANSPORT_UNAVAILABLE" : "KITCHEN_HTTP_" + status;
        completeFailure(attempt, code, retryDelay(status, attempt.number()));
    }

    private int send(Attempt attempt, byte[] body) {
        try {
            return kitchen.send(attempt.restaurantId(), body);
        } catch (RestClientException ex) {
            return TRANSPORT_UNAVAILABLE;
        }
    }

    private void completeFailure(Attempt attempt, String code, Duration delay) {
        if (store.complete(attempt, code, delay) && delay == null) {
            log.warn("Kitchen event delivery stopped: eventId={} code={}", attempt.eventId(), code);
        }
    }

    private static Duration retryDelay(int status, int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return null;
        }
        if (status == 401 || status == 403 || status == 404) {
            return Duration.ofSeconds(30L << (attempt - 1));
        }
        if (status == TRANSPORT_UNAVAILABLE || status == 408 || status == 429 || status >= 500 && status < 600) {
            return Duration.ofSeconds(5L << (attempt - 1));
        }
        return null;
    }
}
