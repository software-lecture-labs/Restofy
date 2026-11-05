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
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real concurrent transactions on PostgreSQL: with the same version exactly one image change wins. */
@SpringBootTest(properties = "restofy.menu.image.allowed-origins=https://images.example")
class ProductImageConcurrencyTests {

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
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
    }

    private UUID newProduct() {
        return service.create(admin, new CreateProductCommand(category, "Tea", null, BigDecimal.ONE, "TRY", BigDecimal.TEN, true))
                .product().getId();
    }

    private UpdateProductCommand image(String url, long version) {
        return new UpdateProductCommand(null, null, null, null, url, null, null, null, null, version);
    }

    private int[] race(int contenders, IntConsumer attempt) throws Exception {
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
    void fourDifferentImagesWithTheSameVersionLetExactlyOneWin() throws Exception {
        UUID id = newProduct();
        int[] outcome = race(4, i -> service.update(admin, id, image("https://images.example/p" + i + ".png", 0L)));
        assertEquals(1, outcome[0]);
        assertEquals(3, outcome[1]);
        String stored = jdbc.queryForObject("SELECT image_url FROM products WHERE id = ?", String.class, id);
        assertTrue(stored.matches("https://images\\.example/p[0-3]\\.png"), stored);
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
    }

    @Test
    void settingAndRemovingAtTheSameTimeWithTheSameVersionLetExactlyOneWin() throws Exception {
        UUID id = newProduct();
        service.update(admin, id, image("https://images.example/first.png", 0L));
        int[] outcome = race(2, i -> service.update(admin, id, image(i == 0 ? "https://images.example/second.png" : "", 1L)));
        assertEquals(1, outcome[0]);
        assertEquals(1, outcome[1]);
        assertEquals(2L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class), "no outcome deletes the product");
    }

    @Test
    void parallelRemovalsOfTheSameImageLetExactlyOneWinTheRestConflict() throws Exception {
        UUID id = newProduct();
        service.update(admin, id, image("https://images.example/first.png", 0L));
        int[] outcome = race(4, _ -> service.update(admin, id, image("", 1L)));
        assertEquals(1, outcome[0]);
        assertEquals(3, outcome[1]);
        assertNull(jdbc.queryForObject("SELECT image_url FROM products WHERE id = ?", String.class, id));
    }

    @Test
    void anImageChangeAndAPriceEditOnTheSameVersionAreMutuallyExclusive() throws Exception {
        UUID id = newProduct();
        int[] outcome = race(2, i -> service.update(admin, id, i == 0
                ? image("https://images.example/a.png", 0L)
                : new UpdateProductCommand(null, null, null, null, null, new BigDecimal("9"), null, null, null, 0L)));
        assertEquals(1, outcome[0]);
        assertEquals(1, outcome[1]);
        var row = jdbc.queryForMap("SELECT image_url, base_price FROM products WHERE id = ?", id);
        boolean imageWon = row.get("image_url") != null;
        boolean priceWon = ((BigDecimal) row.get("base_price")).compareTo(new BigDecimal("9")) == 0;
        assertTrue(imageWon ^ priceWon, "exactly one of the two edits is stored, never both and never neither");
    }
}
