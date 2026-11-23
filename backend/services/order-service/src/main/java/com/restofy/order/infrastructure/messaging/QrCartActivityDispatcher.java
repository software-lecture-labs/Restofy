package com.restofy.order.infrastructure.messaging;

import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.infrastructure.client.QrSessionHttpAdapter;
import com.restofy.order.infrastructure.config.RestClientProperties;
import com.restofy.order.infrastructure.persistence.QrOutboxDeliveryStore;
import com.restofy.order.infrastructure.persistence.QrOutboxDeliveryStore.Attempt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@Profile("order-qr")
public class QrCartActivityDispatcher {
    static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 8;
    private static final int TRANSPORT_UNAVAILABLE = 0;

    private final QrOutboxDeliveryStore store;
    private final QrSessionHttpAdapter qr;
    private final Duration lease;

    public QrCartActivityDispatcher(QrOutboxDeliveryStore store, QrSessionHttpAdapter qr, RestClientProperties timeouts) {
        this.store = store;
        this.qr = qr;
        this.lease = Duration.ofMillis((long) timeouts.connectTimeoutMs() + timeouts.readTimeoutMs()).plusSeconds(5);
    }

    @Scheduled(fixedDelayString = "PT2S")
    @Transactional(propagation = Propagation.NEVER)
    public void dispatch() {
        var tenant = qr.restaurantId();
        store.expireExhausted(tenant, MAX_ATTEMPTS, BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            var claimed = store.claim(tenant, MAX_ATTEMPTS, lease);
            if (claimed.isEmpty()) {
                return;
            }
            deliver(tenant, claimed.get());
        }
    }

    private void deliver(UUID tenant, Attempt attempt) {
        int status = send(attempt);
        if (status == 204) {
            store.delivered(tenant, attempt);
            return;
        }
        String code = status == TRANSPORT_UNAVAILABLE ? "QR_TRANSPORT_UNAVAILABLE" : "QR_HTTP_" + status;
        boolean configurationFailure = status == 401 || status == 403 || status == 404;
        Duration delay = retryDelay(status, attempt.number(), configurationFailure);
        if (store.unsuccessful(tenant, attempt, code, delay) && (delay == null || configurationFailure)) {
            log.warn("QR cart activity delivery unsuccessful: eventId={} attempt={} code={} willRetry={}",
                    attempt.eventId(), attempt.number(), code, delay != null);
        }
    }

    private int send(Attempt attempt) {
        try {
            return qr.sendCartActivity(attempt.restaurantId(), attempt.payload().getBytes(StandardCharsets.UTF_8));
        } catch (BusinessException _) {
            return TRANSPORT_UNAVAILABLE;
        }
    }

    private static Duration retryDelay(int status, int attempt, boolean configurationFailure) {
        if (attempt >= MAX_ATTEMPTS) {
            return null;
        }
        if (configurationFailure) {
            return Duration.ofSeconds(30L << (attempt - 1));
        }
        if (status == TRANSPORT_UNAVAILABLE || status == 408 || status == 429 || (status >= 500 && status < 600)) {
            return Duration.ofSeconds(5L << (attempt - 1));
        }
        return null;
    }
}
