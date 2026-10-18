package com.restofy.menu.api;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.service.CreateProductCommand;
import com.restofy.menu.application.service.ProductOptionService;
import com.restofy.menu.application.service.ProductService;
import com.restofy.menu.application.service.UpdateProductCommand;
import com.restofy.menu.application.service.UpdateProductOptionCommand;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.entity.ProductOption;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.enums.OptionType;
import com.restofy.menu.domain.exception.DuplicateProductOptionException;
import com.restofy.menu.domain.exception.ProductOptionLimitExceededException;
import com.restofy.menu.domain.exception.ProductOptionVersionConflictException;
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

/** Real concurrent transactions on PostgreSQL: the 50-active limit, duplicate names and the option version. */
@SpringBootTest
class ProductOptionConcurrencyTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final AuthenticatedActor admin = new AuthenticatedActor(UUID.randomUUID(), RESTAURANT, ActorRole.ADMIN);

    @Autowired ProductService products;
    @Autowired ProductOptionService options;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private UUID product;

    @BeforeEach
    void aProduct() {
        UUID category = categories.saveAndFlush(Category.root(RESTAURANT, "Main", 0)).getId();
        product = products.create(admin, new CreateProductCommand(category, "Pide", null, BigDecimal.ONE, "TRY", BigDecimal.TEN, true))
                .product().getId();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_options");
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
    }

    private void insertActive(int count, boolean active) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO product_options (id, restaurant_id, product_id, name, option_type, price_delta, min_quantity,
                                                 max_quantity, sort_order, is_active, created_by_user_id, updated_by_user_id)
                    VALUES (?, ?, ?, ?, 'INCLUDED', 0, 0, 1, 0, ?, ?, ?)""", UUID.randomUUID(), RESTAURANT, product,
                    (active ? "Active " : "Disabled ") + i, active, UUID.randomUUID(), UUID.randomUUID());
        }
    }

    private int activeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM product_options WHERE product_id = ? AND is_active", Integer.class, product);
    }

    private ProductOption.Spec extra(String name) {
        return new ProductOption.Spec(OptionType.ADD, name, null, BigDecimal.ONE, 1, 0);
    }

    /** Releases all attempts at the same instant; returns {wins, limit refusals, conflicts, duplicates}. */
    private int[] race(int contenders, IntConsumer attempt) throws Exception {
        var ready = new CountDownLatch(contenders);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        attempt.accept(index);
                        return 0;
                    } catch (ProductOptionLimitExceededException _) {
                        return 1;
                    } catch (ProductOptionVersionConflictException _) {
                        return 2;
                    } catch (DuplicateProductOptionException _) {
                        return 3;
                    }
                }));
            }
            ready.await();
            go.countDown();
            int[] counts = new int[4];
            for (Future<Integer> result : results) {
                try {
                    counts[result.get()]++;
                } catch (ExecutionException unexpected) {
                    fail("Only success, the limit, a version conflict or a duplicate is acceptable, got " + unexpected.getCause());
                }
            }
            return counts;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCreationsNeverExceedFiftyActiveOptions() throws Exception {
        insertActive(45, true);
        int[] outcome = race(12, i -> options.create(admin, product, extra("Extra " + i)));
        assertEquals(5, outcome[0], "exactly the five free slots are filled");
        assertEquals(7, outcome[1], "everyone else is told the limit is reached");
        assertEquals(50, activeCount());
    }

    @Test
    void concurrentReEnablingNeverExceedsFiftyActiveOptionsEither() throws Exception {
        insertActive(47, true);
        insertActive(6, false);
        List<UUID> disabled = jdbc.queryForList("SELECT id FROM product_options WHERE NOT is_active ORDER BY name", UUID.class);
        int[] outcome = race(6, i -> options.update(admin, product, disabled.get(i),
                new UpdateProductOptionCommand(null, null, null, null, null, true, 0L)));
        assertEquals(3, outcome[0], "exactly the three free slots are re-enabled");
        assertEquals(3, outcome[1]);
        assertEquals(50, activeCount());
    }

    @Test
    void creationsAndReEnablingsCompeteForTheSameSlots() throws Exception {
        insertActive(48, true);
        insertActive(3, false);
        List<UUID> disabled = jdbc.queryForList("SELECT id FROM product_options WHERE NOT is_active ORDER BY name", UUID.class);
        int[] outcome = race(6, i -> {
            if (i < 3) {
                options.create(admin, product, extra("New " + i));
            } else {
                options.update(admin, product, disabled.get(i - 3), new UpdateProductOptionCommand(null, null, null, null, null, true, 0L));
            }
        });
        assertEquals(2, outcome[0], "two slots were free");
        assertEquals(4, outcome[1]);
        assertEquals(50, activeCount());
    }

    @Test
    void twoCreationsOfTheSameNameLetExactlyOneWin() throws Exception {
        int[] outcome = race(4, _ -> options.create(admin, product, extra("Cheese")));
        assertEquals(1, outcome[0]);
        assertEquals(3, outcome[3], "the others get a duplicate conflict");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_options", Integer.class));
    }

    @Test
    void fourUpdatesWithTheSameOptionVersionLetExactlyOneWin() throws Exception {
        UUID id = options.create(admin, product, extra("Cheese")).getId();
        int[] outcome = race(4, i -> options.update(admin, product, id,
                new UpdateProductOptionCommand("Winner " + i, null, null, null, null, null, 0L)));
        assertEquals(1, outcome[0]);
        assertEquals(3, outcome[2]);
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM product_options WHERE id = ?", Long.class, id));
        assertTrue(jdbc.queryForObject("SELECT name FROM product_options WHERE id = ?", String.class, id).startsWith("Winner "));
    }

    @Test
    void disablingAndRenamingOnTheSameVersionLetExactlyOneWin() throws Exception {
        UUID id = options.create(admin, product, extra("Cheese")).getId();
        int[] outcome = race(2, i -> options.update(admin, product, id, i == 0
                ? new UpdateProductOptionCommand(null, null, null, null, null, false, 0L)
                : new UpdateProductOptionCommand("Renamed", null, null, null, null, null, 0L)));
        assertEquals(1, outcome[0]);
        assertEquals(1, outcome[2]);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_options", Integer.class), "nothing is deleted");
    }

    @Test
    void optionEditsNeverConflictWithProductEditsBecauseTheirVersionsAreIndependent() throws Exception {
        UUID id = options.create(admin, product, extra("Cheese")).getId();
        int[] outcome = race(2, i -> {
            if (i == 0) {
                options.update(admin, product, id, new UpdateProductOptionCommand("Cheddar", null, null, null, null, null, 0L));
            } else {
                products.update(admin, product, new UpdateProductCommand(
                        null, "Pide 2", null, null, null, null, null, 0L));
            }
        });
        assertEquals(2, outcome[0], "both succeed: they guard different records");
        assertEquals("Cheddar", jdbc.queryForObject("SELECT name FROM product_options WHERE id = ?", String.class, id));
        assertEquals("Pide 2", jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, product));
    }
}
