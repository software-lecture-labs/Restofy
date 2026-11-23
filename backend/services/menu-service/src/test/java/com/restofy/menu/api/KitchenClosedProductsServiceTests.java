package com.restofy.menu.api;

import com.restofy.menu.application.service.KitchenAvailabilityCommand;
import com.restofy.menu.application.service.KitchenAvailabilityCommandService;
import com.restofy.menu.application.service.KitchenClosedProduct;
import com.restofy.menu.application.service.KitchenClosedProductsPage;
import com.restofy.menu.application.service.KitchenClosedProductsService;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.ProductAvailabilityVersionConflictException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.PublicMenuFixture.Spec;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestKitchenCallerProvider;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Kitchen closed-products query on real PostgreSQL: the status filter, the stable order, paging with a matching total,
 * restaurant isolation, the raw catalogue flags, invalid requests, the trusted-caller gate, freshness after a command and
 * the round trip of the listed version into a command's {@code expectedVersion}. The caller is a test-only trusted caller:
 * no real Kitchen connection exists.
 */
@SpringBootTest
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class KitchenClosedProductsServiceTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID BETA = TestPublicScopeConfiguration.RESTAURANT_BETA;
    private static final UUID STAFF = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
    private static final Set<AvailabilityStatus> BOTH = EnumSet.of(AvailabilityStatus.OUT_OF_STOCK, AvailabilityStatus.TEMPORARILY_CLOSED);
    private static final Instant BASE = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired KitchenClosedProductsService closed;
    @Autowired KitchenAvailabilityCommandService commands;
    @Autowired TestKitchenCallerProvider caller;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID category;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        category = fixture.root(ALPHA, "Main");
        caller.actAs(ALPHA, STAFF);
    }

    @AfterEach
    void clean() {
        caller.actAsNobody();
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability_commands");
        fixture.clean();
    }

    private UUID product(UUID restaurant, UUID cat, String name, String status, Instant changedAt) {
        UUID id = fixture.product(restaurant, cat, name, Spec.visible().availability(status));
        jdbc.update("UPDATE product_availability SET changed_at = ? WHERE product_id = ?", Timestamp.from(changedAt), id);
        return id;
    }

    private UUID closedProduct(String name, String status, Instant changedAt) {
        return product(ALPHA, category, name, status, changedAt);
    }

    private List<UUID> ids(KitchenClosedProductsPage page) {
        return page.items().stream().map(KitchenClosedProduct::productId).toList();
    }

    // ---- what is listed ---------------------------------------------------------------------------------------------

    @Test
    void aClosedProductIsListedWithMenusOwnRecord() {
        UUID id = closedProduct("Pide", "OUT_OF_STOCK", BASE);
        jdbc.update("UPDATE product_availability SET reason_code = 'SOLD_OUT', reason_text = 'no dough' WHERE product_id = ?", id);

        KitchenClosedProductsPage page = closed.list(BOTH, 0, 20);

        assertEquals(1, page.totalElements());
        KitchenClosedProduct p = page.items().get(0);
        assertEquals(id, p.productId());
        assertEquals("Pide", p.productName());
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, p.status());
        assertEquals("SOLD_OUT", p.reasonCode());
        assertEquals("no dough", p.reasonText());
        assertEquals(BASE, p.changedAt());
        assertNotNull(p.changedByUserId());
        assertEquals(0L, p.version());
        assertTrue(p.active());
        assertTrue(p.published());
    }

    @Test
    void anAvailableProductAndOneWithoutAnAvailabilityRecordAreNotListed() {
        closedProduct("Open", "AVAILABLE", BASE);
        fixture.product(ALPHA, category, "No record", Spec.visible().availability(null));
        UUID shut = closedProduct("Shut", "TEMPORARILY_CLOSED", BASE);

        assertEquals(List.of(shut), ids(closed.list(BOTH, 0, 20)));
    }

    @Test
    void theStatusFilterSelectsExactlyTheRequestedStatuses() {
        UUID stock = closedProduct("A", "OUT_OF_STOCK", BASE);
        UUID temp = closedProduct("B", "TEMPORARILY_CLOSED", BASE.plusSeconds(1));

        assertEquals(List.of(stock), ids(closed.list(Set.of(AvailabilityStatus.OUT_OF_STOCK), 0, 20)));
        assertEquals(List.of(temp), ids(closed.list(Set.of(AvailabilityStatus.TEMPORARILY_CLOSED), 0, 20)));
        assertEquals(List.of(temp, stock), ids(closed.list(BOTH, 0, 20)));
        assertEquals(1, closed.list(Set.of(AvailabilityStatus.OUT_OF_STOCK), 0, 20).totalElements());
    }

    @Test
    void theNewestClosingComesFirstAndTiesAreBrokenByProductId() {
        UUID oldest = closedProduct("Old", "OUT_OF_STOCK", BASE);
        UUID newest = closedProduct("New", "OUT_OF_STOCK", BASE.plusSeconds(60));
        Instant same = BASE.plusSeconds(30);
        List<UUID> tied = new ArrayList<>(List.of(closedProduct("T1", "OUT_OF_STOCK", same), closedProduct("T2", "OUT_OF_STOCK", same),
                closedProduct("T3", "OUT_OF_STOCK", same)));
        // PostgreSQL orders uuid by unsigned bytes, which is the order of the lower-case hex text (UUID.compareTo is signed).
        tied.sort(java.util.Comparator.comparing(UUID::toString));

        List<UUID> expected = new ArrayList<>();
        expected.add(newest);
        expected.addAll(tied);
        expected.add(oldest);
        assertEquals(expected, ids(closed.list(BOTH, 0, 20)));
    }

    @Test
    void pagingIsStableAndTheTotalCountsTheWholeFilter() {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(0, closedProduct("P" + i, "OUT_OF_STOCK", BASE.plusSeconds(i)));
        }

        KitchenClosedProductsPage first = closed.list(BOTH, 0, 2);
        KitchenClosedProductsPage second = closed.list(BOTH, 1, 2);
        KitchenClosedProductsPage third = closed.list(BOTH, 2, 2);
        KitchenClosedProductsPage beyond = closed.list(BOTH, 3, 2);

        assertEquals(expected.subList(0, 2), ids(first));
        assertEquals(expected.subList(2, 4), ids(second));
        assertEquals(expected.subList(4, 5), ids(third));
        assertTrue(beyond.items().isEmpty());
        for (KitchenClosedProductsPage page : List.of(first, second, third, beyond)) {
            assertEquals(5, page.totalElements());
        }
    }

    @Test
    void nothingClosedIsAnEmptyPageWithTotalZero() {
        closedProduct("Open", "AVAILABLE", BASE);

        KitchenClosedProductsPage page = closed.list(BOTH, 0, 20);

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.totalElements());
    }

    @Test
    void aMaximumSizedPageIsAnswered() {
        closedProduct("A", "OUT_OF_STOCK", BASE);

        assertEquals(1, closed.list(BOTH, 0, KitchenClosedProductsService.MAX_PAGE_SIZE).items().size());
    }

    // ---- archived / draft: listed with raw flags, not interpreted --------------------------------------------------

    @Test
    void anArchivedClosedProductIsListedWithItsRawFlagsNotHidden() {
        UUID id = fixture.product(ALPHA, category, "Old", Spec.visible().archived().availability("OUT_OF_STOCK"));

        KitchenClosedProduct p = closed.list(BOTH, 0, 20).items().get(0);

        assertEquals(id, p.productId());
        assertFalse(p.active());
        assertTrue(p.published());
    }

    @Test
    void anUnpublishedClosedProductIsListedWithItsRawFlag() {
        fixture.product(ALPHA, category, "Draft", Spec.visible().unpublished().availability("TEMPORARILY_CLOSED"));

        KitchenClosedProduct p = closed.list(BOTH, 0, 20).items().get(0);

        assertTrue(p.active());
        assertFalse(p.published());
    }

    // ---- restaurant isolation --------------------------------------------------------------------------------------

    @Test
    void onlyTheCallersRestaurantIsListedAndTheTotalNeverCountsOthers() {
        UUID betaCategory = fixture.root(BETA, "Main");
        product(BETA, betaCategory, "Secret dish", "OUT_OF_STOCK", BASE.plusSeconds(99));
        UUID mine = closedProduct("Mine", "OUT_OF_STOCK", BASE);

        KitchenClosedProductsPage page = closed.list(BOTH, 0, 20);

        assertEquals(List.of(mine), ids(page));
        assertEquals(1, page.totalElements());
    }

    @Test
    void theCallersRestaurantComesFromTheCallerOnly() {
        UUID betaCategory = fixture.root(BETA, "Main");
        UUID theirs = product(BETA, betaCategory, "Theirs", "OUT_OF_STOCK", BASE);
        closedProduct("Mine", "OUT_OF_STOCK", BASE);

        caller.actAs(BETA, STAFF);

        assertEquals(List.of(theirs), ids(closed.list(BOTH, 0, 20)));
    }

    // ---- freshness and the version round trip ---------------------------------------------------------------------

    @Test
    void aCommandIsVisibleInTheNextAnswerAndReopeningRemovesTheProduct() {
        UUID id = fixture.visible(ALPHA, category, "Pide");
        assertTrue(closed.list(BOTH, 0, 20).items().isEmpty());

        commands.handle(new KitchenAvailabilityCommand(UUID.randomUUID(), id, AvailabilityStatus.TEMPORARILY_CLOSED, "CLEANING", null, null));
        KitchenClosedProduct shut = closed.list(BOTH, 0, 20).items().get(0);
        assertEquals(AvailabilityStatus.TEMPORARILY_CLOSED, shut.status());
        assertEquals("CLEANING", shut.reasonCode());
        assertEquals(STAFF, shut.changedByUserId());

        commands.handle(new KitchenAvailabilityCommand(UUID.randomUUID(), id, AvailabilityStatus.AVAILABLE, null, null, null));
        assertTrue(closed.list(BOTH, 0, 20).items().isEmpty());
        assertEquals(0, closed.list(BOTH, 0, 20).totalElements());
    }

    @Test
    void theListedVersionIsTheValueACommandsExpectedVersionIsComparedWith() {
        UUID id = fixture.visible(ALPHA, category, "Pide");
        commands.handle(new KitchenAvailabilityCommand(UUID.randomUUID(), id, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, null));
        long version = closed.list(BOTH, 0, 20).items().get(0).version();

        KitchenAvailabilityCommand stale = new KitchenAvailabilityCommand(UUID.randomUUID(), id, AvailabilityStatus.AVAILABLE, null, null,
                version + 1);
        assertThrows(ProductAvailabilityVersionConflictException.class, () -> commands.handle(stale));
        assertEquals(1, closed.list(BOTH, 0, 20).totalElements(), "a refused command changed nothing");

        commands.handle(new KitchenAvailabilityCommand(UUID.randomUUID(), id, AvailabilityStatus.AVAILABLE, null, null, version));
        assertTrue(closed.list(BOTH, 0, 20).items().isEmpty());
    }

    @Test
    void anAdminChangeOfTheVersionIsVisibleToo() {
        UUID id = closedProduct("Pide", "OUT_OF_STOCK", BASE);
        long before = closed.list(BOTH, 0, 20).items().get(0).version();

        jdbc.update("UPDATE product_availability SET status = 'TEMPORARILY_CLOSED', reason_code = 'X', version = version + 1 "
                + "WHERE product_id = ?", id);

        KitchenClosedProduct after = closed.list(BOTH, 0, 20).items().get(0);
        assertEquals(before + 1, after.version());
        assertEquals(AvailabilityStatus.TEMPORARILY_CLOSED, after.status());
    }

    // ---- invalid requests and the gate ----------------------------------------------------------------------------

    @Test
    void invalidRequestsAreRefusedAsInvalid() {
        Set<AvailabilityStatus> withAvailable = EnumSet.of(AvailabilityStatus.OUT_OF_STOCK, AvailabilityStatus.AVAILABLE);
        List<AvailabilityStatus> withNull = Arrays.asList(AvailabilityStatus.OUT_OF_STOCK, null);

        assertThrows(InvalidProductException.class, () -> closed.list(null, 0, 20));
        assertThrows(InvalidProductException.class, () -> closed.list(Set.of(), 0, 20));
        assertThrows(InvalidProductException.class, () -> closed.list(withNull, 0, 20));
        assertThrows(InvalidProductException.class, () -> closed.list(Set.of(AvailabilityStatus.AVAILABLE), 0, 20));
        assertThrows(InvalidProductException.class, () -> closed.list(withAvailable, 0, 20));
        assertThrows(InvalidProductException.class, () -> closed.list(BOTH, -1, 20));
        assertThrows(InvalidProductException.class, () -> closed.list(BOTH, 0, 0));
        assertThrows(InvalidProductException.class, () -> closed.list(BOTH, 0, -5));
        assertThrows(InvalidProductException.class, () -> closed.list(BOTH, 0, KitchenClosedProductsService.MAX_PAGE_SIZE + 1));
    }

    @Test
    void withoutATrustedCallerEveryRequestIsUnauthenticatedEvenAnInvalidOne() {
        closedProduct("A", "OUT_OF_STOCK", BASE);
        caller.actAsNobody();

        assertThrows(UnauthenticatedException.class, () -> closed.list(BOTH, 0, 20));
        assertThrows(UnauthenticatedException.class, () -> closed.list(null, -1, 0));
    }

    @Test
    void theQueryWritesNothing() {
        closedProduct("A", "OUT_OF_STOCK", BASE);
        String before = snapshot();

        closed.list(BOTH, 0, 20);

        assertEquals(before, snapshot());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product_availability_history", Integer.class));
    }

    private String snapshot() {
        return jdbc.queryForObject("SELECT (SELECT string_agg(product_id || '/' || version || '/' || changed_at, ',' ORDER BY product_id) "
                + "FROM product_availability)", String.class);
    }
}
