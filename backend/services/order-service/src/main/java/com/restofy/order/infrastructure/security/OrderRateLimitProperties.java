package com.restofy.order.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties("restofy.order.rate-limit")
record OrderRateLimitProperties(int windowSeconds, int maxBucketsPerOperation, Map<Operation, Integer> limits) {

    enum Operation {
        CART_READ, CART_WRITE, CHECKOUT, ORDER_READ, CANCELLATION, STAFF_STATUS,
        QR_ORDER_STATE, KITCHEN_STATUS, KITCHEN_CANCELLATION, KITCHEN_DECISION, WAITER_READ, WAITER_SERVE
    }

    OrderRateLimitProperties {
        if (windowSeconds < 1 || windowSeconds > 3600 || maxBucketsPerOperation < 1
                || maxBucketsPerOperation > 50_000 || limits == null || limits.size() != Operation.values().length
                || limits.values().stream().anyMatch(limit -> limit == null || limit < 1 || limit > 100_000)) {
            throw new IllegalArgumentException("Invalid restofy.order.rate-limit configuration");
        }
        limits = Map.copyOf(limits);
    }
}
