package com.restofy.order.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class OrderRateLimitStoreTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSourceProperties database;

    @Test
    void twoStoresShareOneQuotaUnderConcurrentFirstHits() throws Exception {
        String operation = operation();
        String key = key();
        try (var first = new OrderRateLimitStore(database);
             var second = new OrderRateLimitStore(database);
             var workers = Executors.newFixedThreadPool(12)) {
            var start = new CountDownLatch(1);
            var calls = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 24; i++) {
                OrderRateLimitStore store = i % 2 == 0 ? first : second;
                calls.add(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return store.hit(operation, key, 5, 60, 10);
                });
            }
            var results = calls.stream().map(workers::submit).toList();
            start.countDown();
            int admitted = 0;
            for (var result : results) {
                long retry = result.get(30, TimeUnit.SECONDS);
                assertTrue(retry >= 0 && retry <= 60);
                if (retry == 0) {
                    admitted++;
                }
            }
            assertEquals(5, admitted);
        } finally {
            jdbc.update("DELETE FROM order_rate_limits WHERE operation = ?", operation);
        }
    }

    @Test
    void capacityKeepsExistingBucketsAndReusesExpiredSpace() {
        String operation = operation();
        String first = key();
        String second = key();
        try (var store = new OrderRateLimitStore(database)) {
            assertEquals(0, store.hit(operation, first, 2, 60, 1));
            assertEquals(60, store.hit(operation, second, 2, 60, 1));
            assertEquals(0, store.hit(operation, first, 2, 60, 1));
            assertTrue(store.hit(operation, first, 2, 60, 1) > 0);
            expire(operation, first);
            assertEquals(0, store.hit(operation, second, 2, 60, 1));
            expire(operation, second);
            assertEquals(0, store.hit(operation, second, 2, 60, 1));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT attempts FROM order_rate_limits WHERE operation = ? AND bucket_key = ?",
                    Integer.class, operation, second));
        } finally {
            jdbc.update("DELETE FROM order_rate_limits WHERE operation = ?", operation);
        }
    }

    private void expire(String operation, String key) {
        jdbc.update("UPDATE order_rate_limits SET expires_at = now() - interval '1 second'"
                + " WHERE operation = ? AND bucket_key = ?", operation, key);
    }

    private static String operation() {
        return UUID.randomUUID().toString().substring(0, 24);
    }

    private static String key() {
        return SignedServiceRequest.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }
}
