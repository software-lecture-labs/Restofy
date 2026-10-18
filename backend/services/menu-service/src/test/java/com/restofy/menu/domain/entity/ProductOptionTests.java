package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.enums.OptionType;
import com.restofy.menu.domain.exception.InvalidProductException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The pure rules of INCLUDED and ADD options, exactly as decided. */
class ProductOptionTests {

    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private static ProductOption included(String name, Boolean removable) {
        return ProductOption.create(RESTAURANT, PRODUCT, ACTOR,
                new ProductOption.Spec(OptionType.INCLUDED, name, removable, null, null, 0));
    }

    private static ProductOption extra(String name, BigDecimal price, Integer max) {
        return ProductOption.create(RESTAURANT, PRODUCT, ACTOR, new ProductOption.Spec(OptionType.ADD, name, null, price, max, 0));
    }

    private static InvalidProductException rejected(ProductOption.Spec spec) {
        return assertThrows(InvalidProductException.class, () -> ProductOption.create(RESTAURANT, PRODUCT, ACTOR, spec));
    }

    // ---- INCLUDED ----

    @Test
    void aRemovableIncludedIngredientHasMinimumZeroMaximumOneAndNoPrice() {
        var option = included("Onion", true);
        assertEquals(OptionType.INCLUDED, option.getType());
        assertEquals(0, option.getMinQuantity());
        assertEquals(1, option.getMaxQuantity());
        assertEquals(0, BigDecimal.ZERO.compareTo(option.getPriceDelta()));
        assertEquals(Boolean.TRUE, option.isRemovable());
        assertTrue(option.isActive());
        assertEquals(ACTOR, option.getCreatedByUserId());
    }

    @Test
    void aMandatoryIncludedIngredientHasMinimumOne() {
        var option = included("Bread", false);
        assertEquals(1, option.getMinQuantity());
        assertEquals(1, option.getMaxQuantity());
        assertEquals(Boolean.FALSE, option.isRemovable());
    }

    @Test
    void removableMustBeStatedForAnIncludedIngredientThereIsNoDefault() {
        var spec = new ProductOption.Spec(OptionType.INCLUDED, "Onion", null, null, null, 0);
        assertEquals("An included ingredient must state whether it is removable.", rejected(spec).getMessage());
    }

    @Test
    void anIncludedIngredientNeverHasAPriceDifferenceOrAChosenQuantity() {
        var priced = new ProductOption.Spec(OptionType.INCLUDED, "Onion", true, new BigDecimal("0.01"), null, 0);
        assertEquals("An included ingredient has no price difference.", rejected(priced).getMessage());
        var withQuantity = new ProductOption.Spec(OptionType.INCLUDED, "Onion", true, null, 2, 0);
        assertEquals("An included ingredient has a fixed quantity of 1.", rejected(withQuantity).getMessage());
        var negative = new ProductOption.Spec(OptionType.INCLUDED, "Onion", true, new BigDecimal("-1"), null, 0);
        rejected(negative);
    }

    @Test
    void aZeroPriceDifferenceIsAcceptedOnAnIncludedIngredientAsRedundantInput() {
        var option = ProductOption.create(RESTAURANT, PRODUCT, ACTOR,
                new ProductOption.Spec(OptionType.INCLUDED, "Onion", true, new BigDecimal("0.0000"), null, 3));
        assertEquals(0, BigDecimal.ZERO.compareTo(option.getPriceDelta()));
        assertEquals(3, option.getSortOrder());
    }

    // ---- ADD ----

    @Test
    void anExtraIsOptionalWithMinimumZeroAndTheStatedMaximumAndPrice() {
        var option = extra("Extra cheese", new BigDecimal("12.5"), 3);
        assertEquals(OptionType.ADD, option.getType());
        assertEquals(0, option.getMinQuantity());
        assertEquals(3, option.getMaxQuantity());
        assertEquals(new BigDecimal("12.5000"), option.getPriceDelta());
        assertNull(option.isRemovable(), "an extra has no removable flag");
    }

    @Test
    void aFreeExtraIsValid() {
        assertEquals(0, BigDecimal.ZERO.compareTo(extra("Ice", BigDecimal.ZERO, 1).getPriceDelta()));
        assertEquals(0, BigDecimal.ZERO.compareTo(extra("Ice 2", new BigDecimal("0e5"), 1).getPriceDelta()));
        assertEquals(0, BigDecimal.ZERO.compareTo(extra("Ice 3", new BigDecimal("-0"), 1).getPriceDelta()));
    }

    @Test
    void theExtraMaximumQuantityIsOneToTen() {
        assertEquals(1, extra("A", BigDecimal.ONE, 1).getMaxQuantity());
        assertEquals(10, extra("B", BigDecimal.ONE, 10).getMaxQuantity());
        for (int bad : new int[] {0, -1, 11, 100, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var spec = new ProductOption.Spec(OptionType.ADD, "C", null, BigDecimal.ONE, bad, 0);
            assertEquals("The maximum quantity of an extra must be between 1 and 10.", rejected(spec).getMessage(), "max " + bad);
        }
    }

    @Test
    void anExtraNeedsAnExplicitPriceAndMaximumAndCannotBeRemovable() {
        rejected(new ProductOption.Spec(OptionType.ADD, "C", null, null, 1, 0));
        rejected(new ProductOption.Spec(OptionType.ADD, "C", null, BigDecimal.ONE, null, 0));
        var removable = new ProductOption.Spec(OptionType.ADD, "C", true, BigDecimal.ONE, 1, 0);
        assertEquals("Only an included ingredient can be removable.", rejected(removable).getMessage());
    }

    @Test
    void anExtraPriceIsNeverNegativeNeverRoundedAndHasTheDatabaseBounds() {
        var negative = new ProductOption.Spec(OptionType.ADD, "C", null, new BigDecimal("-0.0001"), 1, 0);
        assertEquals("The price difference must not be negative.", rejected(negative).getMessage());
        var excess = new ProductOption.Spec(OptionType.ADD, "C", null, new BigDecimal("1.00001"), 1, 0);
        assertEquals("The price difference must have at most 4 decimal places.", rejected(excess).getMessage());
        var huge = new ProductOption.Spec(OptionType.ADD, "C", null, new BigDecimal("1000000000000000"), 1, 0);
        assertEquals("The price difference is too large.", rejected(huge).getMessage());
        assertEquals(new BigDecimal("999999999999999.9999"), extra("Max", new BigDecimal("999999999999999.9999"), 1).getPriceDelta());
        assertEquals(new BigDecimal("0.0001"), extra("Min", new BigDecimal("0.0001"), 1).getPriceDelta());
    }

    @Test
    void anAbsurdExponentIsRefusedQuicklyWithoutBeingExpanded() {
        var huge = new ProductOption.Spec(OptionType.ADD, "C", null, new BigDecimal("1E+999999999"), 1, 0);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> rejected(huge));
    }

    // ---- name, type, sort order ----

    @Test
    void theNameIsNormalizedLikeOtherNamesAndUnsafeCharactersAreRefused() {
        assertEquals("Extra cheese", extra("  Extra \t cheese\n", BigDecimal.ONE, 1).getName());
        assertEquals("Çiğ köfte 🍅", included("Çiğ köfte 🍅", true).getName());
        assertEquals(150, included("n".repeat(150), true).getName().length());
        for (String bad : new String[] {null, "", "   ", "n".repeat(151), "a\u0000b", "a\u0001b", "a\u007fb", "a\ud800b"}) {
            var spec = new ProductOption.Spec(OptionType.INCLUDED, bad, true, null, null, 0);
            rejected(spec);
        }
    }

    @Test
    void theTypeIsRequiredAndTheSortOrderNeverNegative() {
        rejected(new ProductOption.Spec(null, "C", true, null, null, 0));
        rejected(new ProductOption.Spec(OptionType.INCLUDED, "C", true, null, null, -1));
        assertEquals(Integer.MAX_VALUE, ProductOption.create(RESTAURANT, PRODUCT, ACTOR,
                new ProductOption.Spec(OptionType.INCLUDED, "C", true, null, null, Integer.MAX_VALUE)).getSortOrder());
    }

    @Test
    void missingTenantProductOrActorIsRejected() {
        var spec = new ProductOption.Spec(OptionType.INCLUDED, "C", true, null, null, 0);
        assertThrows(NullPointerException.class, () -> ProductOption.create(null, PRODUCT, ACTOR, spec));
        assertThrows(NullPointerException.class, () -> ProductOption.create(RESTAURANT, null, ACTOR, spec));
        assertThrows(NullPointerException.class, () -> ProductOption.create(RESTAURANT, PRODUCT, null, spec));
    }

    // ---- mutators ----

    @Test
    void mutatorsReportRealChangesOnly() {
        var option = extra("Cheese", new BigDecimal("5"), 2);
        assertFalse(option.rename("  Cheese "));
        assertTrue(option.rename("Cheddar"));
        assertFalse(option.changeSortOrder(0));
        assertTrue(option.changeSortOrder(4));
        assertFalse(option.changePriceDelta(new BigDecimal("5.0000")));
        assertTrue(option.changePriceDelta(BigDecimal.ZERO));
        assertFalse(option.changeMaxQuantity(2));
        assertTrue(option.changeMaxQuantity(10));
        assertFalse(option.changeActive(true));
        assertTrue(option.changeActive(false));
        assertTrue(option.changeActive(true), "a disabled option can be re-enabled");
    }

    @Test
    void anIncludedIngredientCanChangeRemovabilityButNotPriceOrQuantity() {
        var option = included("Onion", true);
        assertFalse(option.changeRemovable(true));
        assertTrue(option.changeRemovable(false));
        assertEquals(Boolean.FALSE, option.isRemovable());
        assertEquals(1, option.getMinQuantity());
        assertFalse(option.changePriceDelta(BigDecimal.ZERO));
        var priced = new BigDecimal("1");
        assertThrows(InvalidProductException.class, () -> option.changePriceDelta(priced));
        assertThrows(InvalidProductException.class, () -> option.changeMaxQuantity(1));
        assertEquals(1, option.getMaxQuantity());
    }

    @Test
    void anExtraCannotBecomeRemovable() {
        var option = extra("Cheese", BigDecimal.ONE, 2);
        assertThrows(InvalidProductException.class, () -> option.changeRemovable(true));
        assertNull(option.isRemovable());
    }

    @Test
    void aRefusedChangeLeavesTheValueAsItWas() {
        var option = extra("Cheese", new BigDecimal("5"), 2);
        var negative = new BigDecimal("-1");
        assertThrows(InvalidProductException.class, () -> option.changePriceDelta(negative));
        assertThrows(InvalidProductException.class, () -> option.changeMaxQuantity(11));
        assertThrows(InvalidProductException.class, () -> option.changeSortOrder(-1));
        assertThrows(InvalidProductException.class, () -> option.rename(" "));
        assertEquals(new BigDecimal("5.0000"), option.getPriceDelta());
        assertEquals(2, option.getMaxQuantity());
        assertEquals("Cheese", option.getName());
    }

    @Test
    void recordUpdatedByRequiresAnActorAndKeepsTheCreator() {
        var option = included("Onion", true);
        var other = UUID.randomUUID();
        option.recordUpdatedBy(other);
        assertEquals(other, option.getUpdatedByUserId());
        assertEquals(ACTOR, option.getCreatedByUserId());
        assertThrows(NullPointerException.class, () -> option.recordUpdatedBy(null));
    }
}
