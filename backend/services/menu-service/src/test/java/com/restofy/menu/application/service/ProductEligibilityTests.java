package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The derived customer-visibility / orderability rule over the independent state axes. */
class ProductEligibilityTests {

    private static final UUID RESTAURANT = UUID.randomUUID();

    private static Product product() {
        Category root = Category.root(RESTAURANT, "Drinks", 0);
        return Product.create(RESTAURANT, UUID.randomUUID(), root.getId(),
                new Product.Details("Tea", null, BigDecimal.ONE, "TRY", BigDecimal.TEN, true));
    }

    @Test
    void activePublishedProductInActiveCategoriesThatIsAvailableCanBeOrdered() {
        var result = ProductEligibility.of(true, true, true, AvailabilityStatus.AVAILABLE);
        assertTrue(result.isVisibleToCustomer());
        assertTrue(result.canOrder());
        assertEquals(AvailabilityStatus.AVAILABLE, result.availability());
    }

    @ParameterizedTest
    @EnumSource(value = AvailabilityStatus.class, names = {"OUT_OF_STOCK", "TEMPORARILY_CLOSED"})
    void closedProductsStayVisibleButCannotBeOrdered(AvailabilityStatus closed) {
        var result = ProductEligibility.of(true, true, true, closed);
        assertTrue(result.isVisibleToCustomer(), "a closed product may be shown as unavailable");
        assertFalse(result.canOrder());
    }

    @Test
    void missingAvailabilityRecordIsNeverOrderable() {
        var result = ProductEligibility.of(true, true, true, null);
        assertFalse(result.canOrder(), "fail closed");
        assertNull(result.availability());
    }

    @ParameterizedTest
    @EnumSource(AvailabilityStatus.class)
    void archivedProductIsNeitherVisibleNorOrderableWhateverItsAvailability(AvailabilityStatus status) {
        var result = ProductEligibility.of(false, true, true, status);
        assertFalse(result.isVisibleToCustomer());
        assertFalse(result.canOrder());
    }

    @ParameterizedTest
    @EnumSource(AvailabilityStatus.class)
    void unpublishedProductIsNeitherVisibleNorOrderableWhateverItsAvailability(AvailabilityStatus status) {
        var result = ProductEligibility.of(true, false, true, status);
        assertFalse(result.isVisibleToCustomer());
        assertFalse(result.canOrder());
    }

    @ParameterizedTest
    @EnumSource(AvailabilityStatus.class)
    void inactiveCategoryChainHidesAndBlocksTheProductWhateverItsAvailability(AvailabilityStatus status) {
        var result = ProductEligibility.of(true, true, false, status);
        assertFalse(result.isVisibleToCustomer());
        assertFalse(result.canOrder());
    }

    /** Exhaustive matrix: 2 x 2 x 2 x (3 statuses + missing) combinations against the written rule. */
    @Test
    void everyCombinationFollowsTheRule() {
        AvailabilityStatus[] statuses = {AvailabilityStatus.AVAILABLE, AvailabilityStatus.OUT_OF_STOCK,
                AvailabilityStatus.TEMPORARILY_CLOSED, null};
        int checked = 0;
        for (boolean active : new boolean[] {true, false}) {
            for (boolean published : new boolean[] {true, false}) {
                for (boolean category : new boolean[] {true, false}) {
                    for (AvailabilityStatus status : statuses) {
                        var result = ProductEligibility.of(active, published, category, status);
                        boolean visible = active && published && category;
                        assertEquals(visible, result.isVisibleToCustomer());
                        assertEquals(visible && status == AvailabilityStatus.AVAILABLE, result.canOrder());
                        assertTrue(!result.canOrder() || result.isVisibleToCustomer(), "orderable implies visible");
                        checked++;
                    }
                }
            }
        }
        assertEquals(32, checked);
    }

    @Test
    void derivesFromTheProductAndItsCategoryPlacement() {
        Category root = Category.root(RESTAURANT, "Drinks", 0);
        Category child = Category.child(RESTAURANT, root.getId(), "Hot", 0);
        Product product = product();
        product.changePublished(true);

        assertTrue(ProductEligibility.of(product, new CategoryPlacement(child, root), AvailabilityStatus.AVAILABLE).canOrder());

        root.deactivate();
        var underInactiveRoot = ProductEligibility.of(product, new CategoryPlacement(child, root), AvailabilityStatus.AVAILABLE);
        assertFalse(underInactiveRoot.isVisibleToCustomer());
        assertFalse(underInactiveRoot.canOrder());
    }

    @Test
    void closedStatusesRequireAReasonAndAvailableDoesNot() {
        assertFalse(AvailabilityStatus.AVAILABLE.requiresReason());
        assertTrue(AvailabilityStatus.OUT_OF_STOCK.requiresReason());
        assertTrue(AvailabilityStatus.TEMPORARILY_CLOSED.requiresReason());
    }

    @Test
    void publicationIsIndependentOfTheArchiveState() {
        Product product = product();
        assertFalse(product.isPublished());
        assertTrue(product.changePublished(true));
        assertFalse(product.changePublished(true), "repeating is a no-op");
        assertTrue(product.changeActive(false));
        assertTrue(product.isPublished(), "archiving never un-publishes");
        assertTrue(product.changePublished(false));
        assertFalse(product.isActive(), "hiding never restores or archives");
        assertTrue(product.changeActive(true));
        assertFalse(product.isPublished(), "restoring never publishes");
    }
}
