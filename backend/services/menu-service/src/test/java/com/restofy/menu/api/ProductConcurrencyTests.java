package com.restofy.menu.api;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.service.CreateProductCommand;
import com.restofy.menu.application.service.ProductService;
import com.restofy.menu.application.service.UpdateProductCommand;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.exception.ProductVersionConflictException;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Real concurrent transactions on PostgreSQL (no outer test transaction). */
@SpringBootTest
class ProductConcurrencyTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final AuthenticatedActor admin = new AuthenticatedActor(UUID.randomUUID(), RESTAURANT, ActorRole.ADMIN);

    @Autowired ProductService service;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID category;

    @BeforeEach
    void aCategory() {
        category = categories.saveAndFlush(Category.root(RESTAURANT, "Main", 0)).getId();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
    }

    private UUID newProduct() {
        return service.create(admin, new CreateProductCommand(category, "Burger", null, new BigDecimal("10"),
                "TRY", new BigDecimal("10"), true)).product().getId();
    }

    /** Runs all attempts at the same instant and returns how many succeeded / conflicted. */
    private int[] race(int contenders, java.util.function.IntConsumer attempt) throws Exception {
        var ready = new CountDownLatch(contenders);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        attempt.accept(index);
                        return true;
                    } catch (ProductVersionConflictException _) {
                        return false;
                    }
                }));
            }
            ready.await();
            go.countDown();
            int wins = 0;
            int conflicts = 0;
            for (Future<Boolean> result : results) {
                try {
                    if (result.get()) wins++; else conflicts++;
                } catch (ExecutionException unexpected) {
                    fail("Only success or a version conflict is acceptable, got " + unexpected.getCause());
                }
            }
            return new int[] {wins, conflicts};
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoUpdatesWithTheSameVersionLetExactlyOneWin() throws Exception {
        UUID id = newProduct();
        int[] outcome = race(4, i -> service.update(admin, id,
                new UpdateProductCommand(null, "Winner" + i, null, null, null, null, null, 0L)));
        assertEquals(1, outcome[0]);
        assertEquals(3, outcome[1]);
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
    }

    @Test
    void anArchiveRacingAnUpdateOnTheSameVersionLetsExactlyOneWin() throws Exception {
        UUID id = newProduct();
        int[] outcome = race(2, i -> {
            if (i == 0) {
                service.changeActive(admin, id, false, 0L);
            } else {
                service.update(admin, id, new UpdateProductCommand(null, "Renamed", null, null, null, null, null, 0L));
            }
        });
        assertEquals(1, outcome[0]);
        assertEquals(1, outcome[1]);
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class));
    }

    @Test
    void twoCategoryMovesWithTheSameVersionLetExactlyOneWin() throws Exception {
        UUID id = newProduct();
        UUID first = categories.saveAndFlush(Category.child(RESTAURANT, category, "Hot", 0)).getId();
        UUID second = categories.saveAndFlush(Category.child(RESTAURANT, category, "Cold", 1)).getId();
        int[] outcome = race(4, i -> service.update(admin, id,
                new UpdateProductCommand(i % 2 == 0 ? first : second, null, null, null, null, null, null, 0L)));
        assertEquals(1, outcome[0]);
        assertEquals(3, outcome[1]);
        UUID stored = jdbc.queryForObject("SELECT category_id FROM products WHERE id = ?", UUID.class, id);
        assertTrue(stored.equals(first) || stored.equals(second), "the winner's category is stored, never a mix");
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
    }

    @Test
    void aCategoryMoveRacingAnArchiveOnTheSameVersionLetsExactlyOneWin() throws Exception {
        UUID id = newProduct();
        UUID other = categories.saveAndFlush(Category.root(RESTAURANT, "Drinks", 1)).getId();
        int[] outcome = race(2, i -> {
            if (i == 0) {
                service.update(admin, id, new UpdateProductCommand(other, null, null, null, null, null, null, 0L));
            } else {
                service.changeActive(admin, id, false, 0L);
            }
        });
        assertEquals(1, outcome[0]);
        assertEquals(1, outcome[1]);
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
    }
}
