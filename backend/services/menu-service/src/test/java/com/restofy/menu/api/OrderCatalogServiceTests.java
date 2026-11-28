package com.restofy.menu.api;

import com.restofy.menu.application.service.OrderCatalogOption;
import com.restofy.menu.application.service.OrderCatalogProduct;
import com.restofy.menu.application.service.OrderCatalogService;
import com.restofy.menu.application.service.PriceIssue;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.enums.OptionType;
import com.restofy.menu.domain.exception.ForbiddenOperationException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.PublicMenuFixture.Spec;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestOrderCallerProvider;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Order catalog lookup on real PostgreSQL: single and batch answers, prices and VAT without rounding, options,
 * every product state, restaurant isolation, invalid requests, the trusted-caller gate and fresh data after a change.
 * The caller is a test-only trusted caller: no real Order connection exists.
 */
@SpringBootTest
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class OrderCatalogServiceTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID BETA = TestPublicScopeConfiguration.RESTAURANT_BETA;

    @Autowired OrderCatalogService catalog;
    @Autowired TestOrderCallerProvider caller;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID category;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        category = fixture.root(ALPHA, "Main");
        caller.actAs(ALPHA);
    }

    @AfterEach
    void clean() {
        caller.actAsNobody();
        jdbc.update("DELETE FROM product_availability_history");
        fixture.clean();
    }

    private OrderCatalogProduct one(UUID restaurant, UUID product) {
        List<OrderCatalogProduct> answer = catalog.lookup(restaurant, List.of(product));
        assertEquals(1, answer.size());
        return answer.get(0);
    }

    private UUID product(String name, String price) {
        return fixture.product(ALPHA, category, name, Spec.visible().priced(price));
    }

    // ---- single and batch lookups ---------------------------------------------------------------------------------

    @Test
    void aSingleProductComesBackWithItsCurrentData() {
        UUID id = product("Pide", "12.50");

        OrderCatalogProduct p = one(ALPHA, id);

        assertEquals(id, p.productId());
        assertEquals("Pide", p.name());
        assertEquals("TRY", p.currency());
        assertEquals(0, new BigDecimal("12.50").compareTo(p.basePrice()));
        assertTrue(p.priceIncludesVat());
        assertEquals(0, BigDecimal.TEN.compareTo(p.vatRatePercent()));
        assertEquals(1250L, p.grossPriceMinor());
        assertEquals(1000, p.taxRateBps());
        assertNull(p.priceIssue());
        assertTrue(p.published());
        assertTrue(p.available());
        assertTrue(p.orderable());
        assertEquals(AvailabilityStatus.AVAILABLE, p.availabilityStatus());
        assertTrue(p.options().isEmpty());
    }

    @Test
    void aBatchComesBackInTheOrderAskedAndUnknownIdsAreLeftOut() {
        UUID a = product("A", "1");
        UUID b = product("B", "2");
        UUID c = product("C", "3");
        UUID unknown = UUID.randomUUID();

        List<OrderCatalogProduct> answer = catalog.lookup(ALPHA, List.of(c, unknown, a, b));

        assertEquals(List.of(c, a, b), answer.stream().map(OrderCatalogProduct::productId).toList());
    }

    @Test
    void repeatedIdsAreAnsweredOnce() {
        UUID a = product("A", "1");

        assertEquals(1, catalog.lookup(ALPHA, List.of(a, a, a)).size());
    }

    @Test
    void fiftyDistinctProductsAreAnsweredInOneCall() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < OrderCatalogService.MAX_PRODUCT_IDS; i++) {
            ids.add(product("P" + i, String.valueOf(i + 1)));
        }

        List<OrderCatalogProduct> answer = catalog.lookup(ALPHA, ids);

        assertEquals(ids, answer.stream().map(OrderCatalogProduct::productId).toList());
    }

    @Test
    void anAllUnknownBatchIsAnEmptyAnswerNotAnError() {
        assertTrue(catalog.lookup(ALPHA, List.of(UUID.randomUUID(), UUID.randomUUID())).isEmpty());
    }

    @Test
    void manyLookupsAtTheSameTimeAllSeeTheSameConsistentAnswer() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(product("P" + i, String.valueOf(i + 1)));
        }
        List<OrderCatalogProduct> expected = catalog.lookup(ALPHA, ids);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<OrderCatalogProduct>>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                Callable<List<OrderCatalogProduct>> task = () -> catalog.lookup(ALPHA, ids);
                futures.add(pool.submit(task));
            }
            for (Future<List<OrderCatalogProduct>> future : futures) {
                assertEquals(expected, future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- price and VAT ---------------------------------------------------------------------------------------------

    @Test
    void aZeroPriceIsAValidFreeProduct() {
        assertEquals(0L, one(ALPHA, product("Water", "0")).grossPriceMinor());
    }

    @Test
    void aPriceFinerThanTheCurrencyIsNeverRoundedAndSaysWhy() {
        OrderCatalogProduct p = one(ALPHA, product("Odd", "12.345"));

        assertNull(p.grossPriceMinor());
        assertEquals(PriceIssue.PRECISION_EXCEEDS_CURRENCY, p.priceIssue());
        assertEquals(0, new BigDecimal("12.345").compareTo(p.basePrice()));
        assertEquals(1000, p.taxRateBps());
    }

    @Test
    void aVatExclusivePriceIsNeverTurnedIntoAGrossGuess() {
        UUID id = product("Net", "100");
        jdbc.update("UPDATE products SET price_includes_vat = FALSE WHERE id = ?", id);

        OrderCatalogProduct p = one(ALPHA, id);

        assertNull(p.grossPriceMinor());
        assertEquals(PriceIssue.VAT_EXCLUSIVE, p.priceIssue());
        assertFalse(p.priceIncludesVat());
        assertEquals(0, new BigDecimal("100").compareTo(p.basePrice()));
        assertEquals(1000, p.taxRateBps());
        assertTrue(p.orderable(), "orderability is a separate axis from price representability");
    }

    @Test
    void aVatRateThatIsNotAWholeNumberOfBasisPointsIsNotRounded() {
        UUID id = product("Odd rate", "10");
        jdbc.update("UPDATE products SET vat_rate = 8.125 WHERE id = ?", id);

        OrderCatalogProduct p = one(ALPHA, id);

        assertNull(p.taxRateBps());
        assertEquals(0, new BigDecimal("8.125").compareTo(p.vatRatePercent()));
        assertEquals(1000L, p.grossPriceMinor());
    }

    @Test
    void theVatRateIsReportedInBasisPoints() {
        UUID id = product("Eight", "10");
        jdbc.update("UPDATE products SET vat_rate = 8.25 WHERE id = ?", id);

        assertEquals(825, one(ALPHA, id).taxRateBps());
    }

    @Test
    void theCurrenciesOwnMinorUnitIsUsed() {
        UUID yen = product("Yen", "100");
        jdbc.update("UPDATE products SET currency = 'JPY' WHERE id = ?", yen);
        UUID dinar = product("Dinar", "1.234");
        jdbc.update("UPDATE products SET currency = 'KWD' WHERE id = ?", dinar);

        assertEquals(100L, one(ALPHA, yen).grossPriceMinor());
        assertEquals(1234L, one(ALPHA, dinar).grossPriceMinor());
    }

    @Test
    void anAmountBeyondTheOrderRangeIsRefusedNotTruncated() {
        OrderCatalogProduct p = one(ALPHA, product("Huge", "99999999999.99"));

        assertNull(p.grossPriceMinor());
        assertEquals(PriceIssue.AMOUNT_OUT_OF_RANGE, p.priceIssue());
    }

    @Test
    void aPriceChangeIsVisibleOnTheNextCall() {
        UUID id = product("Pide", "10");
        assertEquals(1000L, one(ALPHA, id).grossPriceMinor());

        jdbc.update("UPDATE products SET base_price = 12.75, vat_rate = 20 WHERE id = ?", id);

        OrderCatalogProduct after = one(ALPHA, id);
        assertEquals(1275L, after.grossPriceMinor());
        assertEquals(2000, after.taxRateBps());
    }

    @Test
    void aClosedProductIsVisibleAsNotAvailableAndReopeningIsVisibleToo() {
        UUID id = product("Pide", "10");
        assertTrue(one(ALPHA, id).orderable());

        jdbc.update("UPDATE product_availability SET status = 'OUT_OF_STOCK', reason_code = 'SOLD_OUT' WHERE product_id = ?", id);
        OrderCatalogProduct closed = one(ALPHA, id);
        assertFalse(closed.available());
        assertFalse(closed.orderable());
        assertTrue(closed.published());
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, closed.availabilityStatus());

        jdbc.update("UPDATE product_availability SET status = 'AVAILABLE', reason_code = NULL WHERE product_id = ?", id);
        assertTrue(one(ALPHA, id).orderable());
    }

    // ---- options ---------------------------------------------------------------------------------------------------

    @Test
    void optionsAreTransferredWithPriceTypeAndLimitsInAStableOrder() {
        UUID id = product("Burger", "100");
        UUID cheese = fixture.extra(ALPHA, id, "Cheese", "5.50", 3, 2, true);
        UUID onion = fixture.included(ALPHA, id, "Onion", true, 1, true);
        UUID bun = fixture.included(ALPHA, id, "Bun", false, 0, true);
        UUID sauce = fixture.extra(ALPHA, id, "Sauce", "0", 1, 3, true);

        List<OrderCatalogOption> options = one(ALPHA, id).options();

        assertEquals(List.of(bun, onion, cheese, sauce), options.stream().map(OrderCatalogOption::optionId).toList());
        OrderCatalogOption extra = options.get(2);
        assertEquals("Cheese", extra.name());
        assertEquals(OptionType.ADD, extra.type());
        assertEquals(0, new BigDecimal("5.50").compareTo(extra.priceDelta()));
        assertEquals(550L, extra.priceDeltaMinor());
        assertEquals(0, extra.minQuantity());
        assertEquals(3, extra.maxQuantity());
        assertTrue(extra.active());
        OrderCatalogOption mandatory = options.get(0);
        assertEquals(OptionType.INCLUDED, mandatory.type());
        assertEquals(0L, mandatory.priceDeltaMinor());
        assertEquals(1, mandatory.minQuantity());
        assertEquals(0, options.get(1).minQuantity());
        assertEquals(0L, options.get(3).priceDeltaMinor());
    }

    @Test
    void aDisabledOptionIsListedAsInactiveNotHidden() {
        UUID id = product("Burger", "100");
        UUID off = fixture.extra(ALPHA, id, "Bacon", "7", 2, 0, false);

        OrderCatalogOption option = one(ALPHA, id).options().get(0);

        assertEquals(off, option.optionId());
        assertFalse(option.active());
    }

    @Test
    void anOptionPriceFollowsTheProductsPriceRules() {
        UUID net = product("Net", "100");
        jdbc.update("UPDATE products SET price_includes_vat = FALSE WHERE id = ?", net);
        fixture.extra(ALPHA, net, "Paid", "5", 1, 0, true);
        fixture.extra(ALPHA, net, "Free", "0", 1, 1, true);
        UUID fine = product("Fine", "100");
        fixture.extra(ALPHA, fine, "Odd", "1.234", 1, 0, true);

        List<OrderCatalogOption> netOptions = one(ALPHA, net).options();
        assertNull(netOptions.get(0).priceDeltaMinor(), "a net extra is not guessed into a gross one");
        assertEquals(0L, netOptions.get(1).priceDeltaMinor());
        assertNull(one(ALPHA, fine).options().get(0).priceDeltaMinor(), "a too fine extra is not rounded");
    }

    @Test
    void anOptionPriceChangeIsVisibleOnTheNextCall() {
        UUID id = product("Burger", "100");
        UUID extra = fixture.extra(ALPHA, id, "Cheese", "5", 1, 0, true);
        assertEquals(500L, one(ALPHA, id).options().get(0).priceDeltaMinor());

        jdbc.update("UPDATE product_options SET price_delta = 6.5 WHERE id = ?", extra);

        assertEquals(650L, one(ALPHA, id).options().get(0).priceDeltaMinor());
    }

    @Test
    void optionsOfOtherProductsDoNotLeakIntoTheAnswer() {
        UUID a = product("A", "10");
        UUID b = product("B", "10");
        fixture.extra(ALPHA, a, "Only A", "1", 1, 0, true);

        List<OrderCatalogProduct> answer = catalog.lookup(ALPHA, List.of(a, b));

        assertEquals(1, answer.get(0).options().size());
        assertTrue(answer.get(1).options().isEmpty());
    }

    // ---- product states --------------------------------------------------------------------------------------------

    @Test
    void anArchivedProductIsReturnedButNeverOrderable() {
        UUID id = fixture.product(ALPHA, category, "Old", Spec.visible().archived());

        OrderCatalogProduct p = one(ALPHA, id);

        assertFalse(p.published());
        assertFalse(p.orderable());
        assertEquals(1000L, p.grossPriceMinor());
    }

    @Test
    void aDraftProductIsNotPublishedAndNotOrderable() {
        OrderCatalogProduct p = one(ALPHA, fixture.product(ALPHA, category, "Draft", Spec.visible().unpublished()));

        assertFalse(p.published());
        assertFalse(p.orderable());
    }

    @Test
    void aProductWithoutAnAvailabilityRecordIsNeverAvailable() {
        OrderCatalogProduct p = one(ALPHA, fixture.product(ALPHA, category, "Unknown", Spec.visible().availability(null)));

        assertNull(p.availabilityStatus());
        assertFalse(p.available());
        assertFalse(p.orderable());
    }

    @ParameterizedTest
    @EnumSource(value = AvailabilityStatus.class, names = {"OUT_OF_STOCK", "TEMPORARILY_CLOSED"})
    void everyClosedStateIsReportedAndNotOrderable(AvailabilityStatus status) {
        OrderCatalogProduct p = one(ALPHA, fixture.product(ALPHA, category, "Closed", Spec.visible().availability(status.name())));

        assertEquals(status, p.availabilityStatus());
        assertFalse(p.available());
        assertFalse(p.orderable());
        assertTrue(p.published());
    }

    @Test
    void aProductInAnInactiveCategoryIsNotPublished() {
        UUID off = fixture.category(ALPHA, null, "Off", 1, false);
        UUID id = fixture.visible(ALPHA, off, "Hidden");

        assertFalse(one(ALPHA, id).published());
        assertFalse(one(ALPHA, id).orderable());
    }

    @Test
    void aProductInASubcategoryOfAnInactiveRootIsNotPublished() {
        UUID root = fixture.category(ALPHA, null, "Root", 1, false);
        UUID sub = fixture.category(ALPHA, root, "Sub", 0, true);
        UUID id = fixture.visible(ALPHA, sub, "Deep");

        assertFalse(one(ALPHA, id).published());
    }

    @Test
    void aProductInAnActiveSubcategoryIsPublished() {
        UUID root = fixture.category(ALPHA, null, "Root", 1, true);
        UUID sub = fixture.category(ALPHA, root, "Sub", 0, true);

        assertTrue(one(ALPHA, fixture.visible(ALPHA, sub, "Deep")).orderable());
    }

    // ---- restaurant isolation --------------------------------------------------------------------------------------

    @Test
    void aProductOfAnotherRestaurantIsAbsentNotForbiddenAndNothingAboutItLeaks() {
        UUID betaCategory = fixture.root(BETA, "Main");
        UUID foreign = fixture.visible(BETA, betaCategory, "Secret dish");
        UUID mine = product("Mine", "10");

        List<OrderCatalogProduct> answer = catalog.lookup(ALPHA, List.of(foreign, mine));

        assertEquals(List.of(mine), answer.stream().map(OrderCatalogProduct::productId).toList());
        assertTrue(catalog.lookup(ALPHA, List.of(foreign)).isEmpty());
    }

    @Test
    void aCallerOfOneRestaurantCannotAskForAnother() {
        UUID betaCategory = fixture.root(BETA, "Main");
        UUID foreign = fixture.visible(BETA, betaCategory, "Secret dish");

        assertThrows(ForbiddenOperationException.class, () -> catalog.lookup(BETA, List.of(foreign)));
    }

    @Test
    void theOptionsOfAnotherRestaurantNeverAppear() {
        UUID betaCategory = fixture.root(BETA, "Main");
        UUID foreign = fixture.visible(BETA, betaCategory, "Secret dish");
        fixture.extra(BETA, foreign, "Secret extra", "9", 1, 0, true);
        UUID mine = product("Mine", "10");

        assertTrue(one(ALPHA, mine).options().isEmpty());
        caller.actAs(BETA);
        assertEquals(1, one(BETA, foreign).options().size());
    }

    // ---- invalid requests and the gate ----------------------------------------------------------------------------

    @Test
    void invalidRequestsAreRefusedAsInvalid() {
        UUID id = product("A", "1");
        List<UUID> withNull = Arrays.asList(id, null);
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= OrderCatalogService.MAX_PRODUCT_IDS; i++) {
            tooMany.add(UUID.randomUUID());
        }

        assertThrows(InvalidProductException.class, () -> catalog.lookup(ALPHA, null));
        assertThrows(InvalidProductException.class, () -> catalog.lookup(ALPHA, List.of()));
        assertThrows(InvalidProductException.class, () -> catalog.lookup(ALPHA, withNull));
        assertThrows(InvalidProductException.class, () -> catalog.lookup(ALPHA, tooMany));
        assertThrows(InvalidProductException.class, () -> catalog.lookup(null, List.of(id)));
    }

    @Test
    void withoutATrustedCallerEveryRequestIsUnauthenticatedEvenAnInvalidOne() {
        UUID id = product("A", "1");
        caller.actAsNobody();

        assertThrows(UnauthenticatedException.class, () -> catalog.lookup(ALPHA, List.of(id)));
        assertThrows(UnauthenticatedException.class, () -> catalog.lookup(ALPHA, null));
        assertThrows(UnauthenticatedException.class, () -> catalog.lookup(null, List.of()));
    }

    @Test
    void aWrongRestaurantIsForbiddenBeforeTheIdsAreEvenLooked() {
        assertThrows(ForbiddenOperationException.class, () -> catalog.lookup(BETA, null));
    }

    @Test
    void theLookupWritesNothing() {
        UUID id = product("A", "1");
        fixture.extra(ALPHA, id, "X", "1", 1, 0, true);
        String before = snapshot();

        catalog.lookup(ALPHA, List.of(id, UUID.randomUUID()));

        assertEquals(before, snapshot());
    }

    private String snapshot() {
        return jdbc.queryForObject("SELECT (SELECT string_agg(id || '/' || version, ',' ORDER BY id) FROM products) || '|' || "
                + "(SELECT string_agg(id || '/' || version, ',' ORDER BY id) FROM product_options) || '|' || "
                + "(SELECT string_agg(id || '/' || version, ',' ORDER BY id) FROM product_availability)", String.class);
    }
}
