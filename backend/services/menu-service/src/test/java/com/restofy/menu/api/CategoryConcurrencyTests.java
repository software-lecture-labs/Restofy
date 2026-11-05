package com.restofy.menu.api;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.service.CategoryService;
import com.restofy.menu.application.service.UpdateCategoryCommand;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.exception.CategoryVersionConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Real concurrent transactions on PostgreSQL (no outer test transaction). */
@SpringBootTest
class CategoryConcurrencyTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final AuthenticatedActor admin = new AuthenticatedActor(UUID.randomUUID(), RESTAURANT, ActorRole.ADMIN);

    @Autowired CategoryService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM categories");
    }

    @Test
    void twoUpdatesWithTheSameVersionLetExactlyOneWin() throws Exception {
        UUID id = service.createRoot(admin, "Drinks", 0).getId();
        int contenders = 4;
        var ready = new CountDownLatch(contenders);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                String newName = "Winner" + i;
                Callable<String> attempt = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        service.updateRoot(admin, id, new UpdateCategoryCommand(newName, null, null, 0L));
                        return "OK";
                    } catch (CategoryVersionConflictException conflict) {
                        return "CONFLICT";
                    }
                };
                results.add(pool.submit(attempt));
            }
            ready.await();
            go.countDown();
            long wins = 0, conflicts = 0;
            for (Future<String> result : results) {
                try {
                    String outcome = result.get();
                    if (outcome.equals("OK")) wins++; else conflicts++;
                } catch (ExecutionException unexpected) {
                    fail("Only OK or a version conflict is acceptable, got " + unexpected.getCause());
                }
            }
            assertEquals(1, wins);
            assertEquals(contenders - 1, conflicts);
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM categories WHERE id = ?", Long.class, id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCreatesOfTheSameNameYieldOneRowAndConflicts() throws Exception {
        int contenders = 4;
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        service.createRoot(admin, "Drinks", 0);
                        return "OK";
                    } catch (com.restofy.menu.domain.exception.DuplicateCategoryNameException duplicate) {
                        return "CONFLICT";
                    }
                }));
            }
            go.countDown();
            int wins = 0;
            for (Future<String> result : results) {
                if (result.get().equals("OK")) wins++;
            }
            assertEquals(1, wins);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
