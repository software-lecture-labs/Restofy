package com.restofy.menu.api;

import com.restofy.menu.application.service.PublicMenuService;
import com.restofy.menu.application.service.PublicProductQuery;
import com.restofy.menu.application.service.PublicProductSort;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.infrastructure.persistence.repository.PublicProductSearchRepository;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a public read really guarantees on PostgreSQL: it runs in one read-only REPEATABLE READ transaction, so the
 * page, the total and the availability loaded for it come from one snapshot, never a mixture. It is a snapshot of
 * the moment the read began, not a live view: a change committed a moment later shows on the next read.
 */
@SpringBootTest
@Import(TestPublicScopeConfiguration.class)
class PublicReadConsistencyTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final String REF = TestPublicScopeConfiguration.ALPHA_REF;
    private static final PublicProductQuery ALL = new PublicProductQuery(null, null, null);

    @Autowired PublicMenuService publicMenu;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PublicProductSearchRepository search;

    private PublicMenuFixture fixture;
    private UUID category;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        category = fixture.root(ALPHA, "Main");
    }

    @AfterEach
    void clean() {
        Mockito.reset(search);
        fixture.clean();
    }

    /** Runs a write on its own connection and commits it, as another request would. */
    private void commitFromAnotherConnection(Runnable write) throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(write).get();
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void aPublicReadRunsInAReadOnlyRepeatableReadTransaction() {
        fixture.visible(ALPHA, category, "Pide");
        AtomicReference<String> isolation = new AtomicReference<>();
        AtomicReference<String> readOnly = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            isolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
            readOnly.set(jdbc.queryForObject("SHOW transaction_read_only", String.class));
            return invocation.callRealMethod();
        }).when(search).search(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());

        publicMenu.products(REF, ALL, PublicProductSort.DEFAULT, 0, 20);

        assertEquals("repeatable read", isolation.get());
        assertEquals("on", readOnly.get());
    }

    @Test
    void aSingleProductLookupUsesTheSameSnapshotIsolation() {
        UUID id = fixture.visible(ALPHA, category, "Pide");
        AtomicReference<String> isolation = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            isolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
            return invocation.callRealMethod();
        }).when(search).findListed(Mockito.any(), Mockito.any());

        publicMenu.listedProduct(REF, id);

        assertEquals("repeatable read", isolation.get());
    }

    @Test
    void aChangeCommittedWhileAReadIsRunningDoesNotSplitThatReadAndShowsOnTheNext() {
        UUID id = fixture.visible(ALPHA, category, "Pide");
        AtomicBoolean changed = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (changed.compareAndSet(false, true)) {
                commitFromAnotherConnection(() -> jdbc.update(
                        "UPDATE product_availability SET status = 'OUT_OF_STOCK', reason_code = 'SOLD_OUT' WHERE product_id = ?", id));
            }
            return result;
        }).when(search).search(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());

        var during = publicMenu.products(REF, ALL, PublicProductSort.DEFAULT, 0, 20);
        assertTrue(changed.get(), "the change was committed in the middle of the read");
        var card = during.getContent().get(0);
        assertEquals(AvailabilityStatus.AVAILABLE, card.view().availability().getStatus(), "the whole read saw one snapshot");
        assertTrue(card.view().eligibility().canOrder());

        var next = publicMenu.products(REF, ALL, PublicProductSort.DEFAULT, 0, 20);
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, next.getContent().get(0).view().availability().getStatus());
        assertFalse(next.getContent().get(0).view().eligibility().canOrder());
    }

    @Test
    void aProductHiddenWhileAReadIsRunningIsStillCountedAndListedByThatSameRead() {
        UUID id = fixture.visible(ALPHA, category, "Pide");
        fixture.visible(ALPHA, category, "Çay");
        AtomicBoolean changed = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (changed.compareAndSet(false, true)) {
                commitFromAnotherConnection(() -> jdbc.update("UPDATE products SET is_active = FALSE WHERE id = ?", id));
            }
            return result;
        }).when(search).search(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());

        var during = publicMenu.products(REF, ALL, PublicProductSort.DEFAULT, 0, 20);
        assertEquals(2, during.getTotalElements());
        assertEquals(2, during.getContent().size(), "the total and the items agree");

        var next = publicMenu.products(REF, ALL, PublicProductSort.DEFAULT, 0, 20);
        assertEquals(1, next.getTotalElements());
        assertEquals(1, next.getContent().size());
    }

    @Test
    void concurrentWritesNeverMakeAReadInconsistentWithItself() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(fixture.visible(ALPHA, category, "Item " + i));
        }
        String[] statuses = {"AVAILABLE", "OUT_OF_STOCK", "TEMPORARILY_CLOSED"};
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            Future<?> writer = pool.submit(() -> {
                int n = 0;
                while (running.get()) {
                    UUID id = ids.get(n % ids.size());
                    String status = statuses[n % statuses.length];
                    jdbc.update("UPDATE product_availability SET status = ?, reason_code = ? WHERE product_id = ?",
                            status, "AVAILABLE".equals(status) ? null : "SOLD_OUT", id);
                    jdbc.update("UPDATE products SET is_published = ? WHERE id = ?", n % 7 != 0, id);
                    n++;
                }
            });
            List<Future<Integer>> readers = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                Callable<Integer> reader = () -> {
                    int checked = 0;
                    for (int i = 0; i < 60; i++) {
                        var page = publicMenu.products(REF, ALL, PublicProductSort.DEFAULT, 0, 100);
                        assertEquals(page.getTotalElements(), page.getContent().size(), "total and items come from one snapshot");
                        for (var card : page.getContent()) {
                            AvailabilityStatus status = card.view().availability().getStatus();
                            assertEquals(status == AvailabilityStatus.AVAILABLE, card.view().eligibility().canOrder());
                            assertTrue(card.view().product().isPublished() && card.view().product().isActive());
                        }
                        checked++;
                    }
                    return checked;
                };
                readers.add(pool.submit(reader));
            }
            for (Future<Integer> reader : readers) {
                assertEquals(60, reader.get());
            }
            running.set(false);
            writer.get();
        } finally {
            running.set(false);
            pool.shutdownNow();
        }
    }
}
