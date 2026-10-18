package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.exception.InvalidProductException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure domain rules; the same limits are re-checked by the database in ProductSchemaPostgresIntegrationTests. */
class ProductTests {

    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();

    private static Product product() {
        return Product.create(RESTAURANT, ACTOR, CATEGORY, new Product.Details("Burger", "Grilled", new BigDecimal("12.5"), "TRY",
                new BigDecimal("10"), true));
    }

    @Test
    void newProductIsAnActiveUnpublishedDraftWithExactDecimalScale() {
        Product p = product();
        assertTrue(p.isActive());
        assertFalse(p.isPublished());
        assertEquals(new BigDecimal("12.5000"), p.getBasePrice());
        assertEquals(new BigDecimal("10.0000"), p.getVatRate());
        assertEquals(ACTOR, p.getCreatedByUserId());
        assertEquals(ACTOR, p.getUpdatedByUserId());
        assertNotNull(p.getId());
    }

    @Test
    void missingTenantActorOrCategoryIsRejected() {
        Product.Details details = new Product.Details("A", null, BigDecimal.ONE, "TRY", BigDecimal.ZERO, true);
        assertThrows(NullPointerException.class, () -> Product.create(null, ACTOR, CATEGORY, details));
        assertThrows(NullPointerException.class, () -> Product.create(RESTAURANT, null, CATEGORY, details));
        assertThrows(NullPointerException.class, () -> Product.create(RESTAURANT, ACTOR, null, details));
    }

    @Test
    void nameRules() {
        assertEquals("A B", Product.normalizeName("  A \t\n B  "));
        assertEquals("A B", Product.normalizeName("A B"));
        assertThrows(InvalidProductException.class, () -> Product.normalizeName(null));
        assertThrows(InvalidProductException.class, () -> Product.normalizeName("   "));
        assertEquals(200, Product.normalizeName("x".repeat(200)).length());
        String tooLong = "x".repeat(201);
        assertThrows(InvalidProductException.class, () -> Product.normalizeName(tooLong));
    }

    @Test
    void descriptionRules() {
        assertNull(Product.normalizeDescription(null));
        assertNull(Product.normalizeDescription("  \n "));
        assertEquals("tasty", Product.normalizeDescription("  tasty "));
        assertEquals(Product.DESCRIPTION_MAX_LENGTH,
                Product.normalizeDescription("d".repeat(Product.DESCRIPTION_MAX_LENGTH)).length());
        String tooLong = "d".repeat(Product.DESCRIPTION_MAX_LENGTH + 1);
        assertThrows(InvalidProductException.class, () -> Product.normalizeDescription(tooLong));
    }

    @Test
    void priceRules() {
        assertEquals(new BigDecimal("0.0000"), Product.normalizePrice(BigDecimal.ZERO));
        assertEquals(new BigDecimal("12.3400"), Product.normalizePrice(new BigDecimal("12.34")));
        assertEquals(new BigDecimal("1.2000"), Product.normalizePrice(new BigDecimal("1.200000")), "trailing zeros are not extra precision");
        assertEquals(new BigDecimal("999999999999999.9999"), Product.normalizePrice(new BigDecimal("999999999999999.9999")));
        assertThrows(InvalidProductException.class, () -> Product.normalizePrice(null));
        BigDecimal negativePrice = new BigDecimal("-0.0001");
        assertThrows(InvalidProductException.class, () -> Product.normalizePrice(negativePrice));
        BigDecimal excessPrecision = new BigDecimal("1.00001");
        assertThrows(InvalidProductException.class, () -> Product.normalizePrice(excessPrecision), "no silent rounding");
        BigDecimal tooLarge = new BigDecimal("1000000000000000");
        assertThrows(InvalidProductException.class, () -> Product.normalizePrice(tooLarge));
        BigDecimal exponentTooLarge = new BigDecimal("1E+20");
        assertThrows(InvalidProductException.class, () -> Product.normalizePrice(exponentTooLarge));
    }

    @Test
    void vatRateRules() {
        assertEquals(new BigDecimal("0.0000"), Product.normalizeVatRate(BigDecimal.ZERO));
        assertEquals(new BigDecimal("100.0000"), Product.normalizeVatRate(new BigDecimal("100")));
        assertThrows(InvalidProductException.class, () -> Product.normalizeVatRate(null));
        BigDecimal negativeRate = new BigDecimal("-0.0001");
        assertThrows(InvalidProductException.class, () -> Product.normalizeVatRate(negativeRate));
        BigDecimal aboveMaximum = new BigDecimal("100.0001");
        assertThrows(InvalidProductException.class, () -> Product.normalizeVatRate(aboveMaximum));
        BigDecimal excessPrecision = new BigDecimal("10.00001");
        assertThrows(InvalidProductException.class, () -> Product.normalizeVatRate(excessPrecision));
    }

    @Test
    void currencyRules() {
        assertEquals("EUR", Product.normalizeCurrency("EUR"));
        for (String bad : new String[] {null, "", "eu", "eur", "EURO", "E1R", " EUR"}) {
            assertThrows(InvalidProductException.class, () -> Product.normalizeCurrency(bad), String.valueOf(bad));
        }
    }

    @Test
    void mutatorsReportRealChangesOnly() {
        Product p = product();
        assertFalse(p.rename("Burger"));
        assertTrue(p.rename("Cheeseburger"));
        assertFalse(p.describe("Grilled"));
        assertTrue(p.describe(null));
        assertFalse(p.describe(" "), "already null");
        assertFalse(p.changeBasePrice(new BigDecimal("12.50")));
        assertTrue(p.changeBasePrice(new BigDecimal("13")));
        assertFalse(p.changeCurrency("TRY"));
        assertTrue(p.changeCurrency("USD"));
        assertFalse(p.changeVatRate(new BigDecimal("10.0")));
        assertTrue(p.changeVatRate(new BigDecimal("8")));
        assertFalse(p.changePriceIncludesVat(true));
        assertTrue(p.changePriceIncludesVat(false));
        assertFalse(p.moveToCategory(CATEGORY));
        assertTrue(p.moveToCategory(UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> p.moveToCategory(null));
    }

    @Test
    void archivingIsASoftStateAndNeverTouchesPublication() {
        Product p = product();
        assertTrue(p.changeActive(false));
        assertFalse(p.isActive());
        assertFalse(p.isPublished());
        assertFalse(p.changeActive(false));
        assertTrue(p.changeActive(true));
    }

    @Test
    void invalidMutationDoesNotLeaveAHalfAppliedValue() {
        Product p = product();
        BigDecimal negativePrice = new BigDecimal("-1");
        assertThrows(InvalidProductException.class, () -> p.changeBasePrice(negativePrice));
        assertEquals(new BigDecimal("12.5000"), p.getBasePrice());
        assertThrows(InvalidProductException.class, () -> p.rename(" "));
        assertEquals("Burger", p.getName());
    }

    @Test
    void recordUpdatedByRequiresAnActor() {
        Product p = product();
        UUID other = UUID.randomUUID();
        p.recordUpdatedBy(other);
        assertEquals(other, p.getUpdatedByUserId());
        assertEquals(ACTOR, p.getCreatedByUserId());
        assertThrows(NullPointerException.class, () -> p.recordUpdatedBy(null));
    }

    @Test
    void currencyMustBeARealIsoCode() {
        for (String ok : new String[] {"TRY", "USD", "EUR", "GBP", "JPY"}) {
            assertEquals(ok, Product.normalizeCurrency(ok));
        }
        for (String bad : new String[] {"ZZZ", "ABC", "QQQ", "AAA"}) {
            var failure = assertThrows(InvalidProductException.class, () -> Product.normalizeCurrency(bad), bad);
            assertEquals("Currency must be a valid ISO 4217 currency code.", failure.getMessage());
        }
    }

    @Test
    void absurdExponentsAreRefusedQuicklyWithoutBeingExpanded() {
        for (String huge : new String[] {"1E+999999999", "9.9E+2147483646", "1E+400"}) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                BigDecimal value = new BigDecimal(huge);
                assertThrows(InvalidProductException.class, () -> Product.normalizePrice(value), huge);
                assertThrows(InvalidProductException.class, () -> Product.normalizeVatRate(value), huge);
            });
        }
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            BigDecimal value = new BigDecimal("1E-999999999");
            assertThrows(InvalidProductException.class, () -> Product.normalizePrice(value),
                    "a nonzero value far below 0.0001 needs more than four decimals");
        });
        assertEquals(new BigDecimal("0.0000"), Product.normalizePrice(new BigDecimal("0E+999999999")));
        assertEquals(new BigDecimal("1000.0000"), Product.normalizePrice(new BigDecimal("1E+3")));
    }

    @Test
    void priceBoundaryIsExactlyFifteenIntegerAndFourFractionDigits() {
        assertEquals(new BigDecimal("999999999999999.9999"), Product.normalizePrice(new BigDecimal("999999999999999.9999")));
        BigDecimal aboveMaximum = new BigDecimal("1000000000000000");
        assertThrows(InvalidProductException.class, () -> Product.normalizePrice(aboveMaximum));
        BigDecimal aboveMaximumWithScale = new BigDecimal("1000000000000000.0000");
        assertThrows(InvalidProductException.class, () -> Product.normalizePrice(aboveMaximumWithScale));
        assertEquals(new BigDecimal("100.0000"), Product.normalizeVatRate(new BigDecimal("100.0000")));
        BigDecimal aboveMaximumVat = new BigDecimal("100.0001");
        assertThrows(InvalidProductException.class, () -> Product.normalizeVatRate(aboveMaximumVat));
        assertEquals(new BigDecimal("0.0001"), Product.normalizeVatRate(new BigDecimal("0.0001")));
    }

    @Test
    void controlCharactersAndLoneSurrogatesAreRefusedInTextFields() {
        for (String bad : new String[] {"a\u0000b", "a\u0001b", "a\u007fb", "a\u0085b", "a\u009fb", "a\ud800b", "\udc00", "ab\ud83d"}) {
            assertThrows(InvalidProductException.class, () -> Product.normalizeName(bad), "name " + bad.length());
            assertThrows(InvalidProductException.class, () -> Product.normalizeDescription(bad), "description");
        }
        assertEquals("Crème brûlée 🍰 寿司", Product.normalizeName("Crème brûlée 🍰 寿司"), "valid surrogate pairs are fine");
        assertEquals("a b", Product.normalizeName("a\t\n b"), "tabs and line breaks in a name collapse to one space");
        assertEquals("line1\nline2\tx", Product.normalizeDescription("line1\nline2\tx"), "a description may be multi-line");
    }

    @Test
    void genuineMonetaryCurrenciesAreAcceptedIncludingTheOnesThatStartWithX() {
        for (String ok : new String[] {"TRY", "USD", "EUR", "GBP", "JPY", "CHF", "SAR", "AED", "CAD", "AUD", "XAF", "XOF", "XCD", "XPF", "XCG"}) {
            assertEquals(ok, Product.normalizeCurrency(ok), ok);
        }
    }

    @Test
    void specialPurposeAndFundsCodesCannotBeASalePrice() {
        for (String special : new String[] {"XXX", "XTS", "XAU", "XAG", "XPD", "XPT", "XDR", "XBA", "XBB", "XBC", "XBD", "XSU",
                "XUA", "XFU", "XFO", "XAD", "BOV", "CHE", "CHW", "CLF", "COU", "MXV", "USN", "USS", "UYI", "UYW"}) {
            var failure = assertThrows(InvalidProductException.class, () -> Product.normalizeCurrency(special), special);
            assertTrue(failure.getMessage().equals("Currency cannot be used for prices.")
                    || failure.getMessage().equals("Currency must be a valid ISO 4217 currency code."), failure.getMessage());
        }
        assertEquals("Currency cannot be used for prices.",
                assertThrows(InvalidProductException.class, () -> Product.normalizeCurrency("XXX")).getMessage());
        assertEquals("Currency cannot be used for prices.",
                assertThrows(InvalidProductException.class, () -> Product.normalizeCurrency("XAU")).getMessage());
    }

    /** Guards against a newer JDK adding a pseudo-currency: anything without fraction digits must stay rejected. */
    @Test
    void everyJdkPseudoCurrencyIsRejectedAndEveryOtherJdkCurrencyOutsideTheDeniedSetIsAccepted() {
        java.util.Set<String> denied = java.util.Set.of("XXX", "XTS", "XAU", "XAG", "XPD", "XPT", "XDR", "XBA", "XBB", "XBC", "XBD",
                "XSU", "XUA", "XFU", "XFO", "XAD", "BOV", "CHE", "CHW", "CLF", "COU", "MXV", "USN", "USS", "UYI", "UYW");
        int accepted = 0;
        for (java.util.Currency currency : java.util.Currency.getAvailableCurrencies()) {
            String code = currency.getCurrencyCode();
            if (currency.getDefaultFractionDigits() < 0 || denied.contains(code)) {
                assertThrows(InvalidProductException.class, () -> Product.normalizeCurrency(code), code);
            } else {
                assertEquals(code, Product.normalizeCurrency(code), code);
                accepted++;
            }
        }
        assertTrue(accepted > 150, "a realistic set of real currencies is accepted, got " + accepted);
    }

    @Test
    void ingredientsTextRules() {
        assertNull(Product.normalizeIngredientsText(null));
        assertNull(Product.normalizeIngredientsText(""));
        assertNull(Product.normalizeIngredientsText(" \t\n "), "blank is never stored");
        assertEquals("Flour, water, salt", Product.normalizeIngredientsText("  Flour, water, salt \n"));
        assertEquals("Un, su\ntuz", Product.normalizeIngredientsText("Un, su\ntuz"), "multi-line is allowed");
        assertEquals("Çiğ köfte 🍅 寿司", Product.normalizeIngredientsText("Çiğ köfte 🍅 寿司"));
        assertEquals(1000, Product.normalizeIngredientsText("i".repeat(1000)).length());
        String oneTooLong = "i".repeat(1001);
        assertThrows(InvalidProductException.class, () -> Product.normalizeIngredientsText(oneTooLong));
        for (String bad : new String[] {"a\u0000b", "a\u0001b", "a\u007fb", "a\u0085b", "a\ud800b", "\udc00", "ab\ud83d"}) {
            assertThrows(InvalidProductException.class, () -> Product.normalizeIngredientsText(bad), "bad " + bad.length());
        }
    }

    @Test
    void ingredientsTextIsOptionalOnCreateAndIndependentOfEveryOtherField() {
        Product without = product();
        assertNull(without.getIngredientsText(), "an old-style product has none and nothing is invented");
        Product with = Product.create(RESTAURANT, ACTOR, CATEGORY, new Product.Details("Pide", "Hot", " Flour, cheese ",
                new BigDecimal("12.5"), "TRY", BigDecimal.TEN, true));
        assertEquals("Flour, cheese", with.getIngredientsText());
        assertEquals("Hot", with.getDescription(), "the description is a separate field");
        assertEquals(new BigDecimal("12.5000"), with.getBasePrice(), "the text never touches the price");
        assertFalse(with.isPublished());
        var unsafe = new Product.Details("Pide", null, "bad\u0000", BigDecimal.ONE, "TRY", BigDecimal.TEN, true);
        assertThrows(InvalidProductException.class, () -> Product.create(RESTAURANT, ACTOR, CATEGORY, unsafe));
    }

    @Test
    void ingredientsTextMutatorSetsReplacesClearsAndReportsRealChangesOnly() {
        Product p = product();
        assertFalse(p.changeIngredientsText(null));
        assertFalse(p.changeIngredientsText("  "), "already null");
        assertTrue(p.changeIngredientsText("Flour"));
        assertFalse(p.changeIngredientsText(" Flour "));
        assertTrue(p.changeIngredientsText("Flour, salt"));
        assertTrue(p.changeIngredientsText(" "), "a blank text clears it");
        assertNull(p.getIngredientsText());
        assertThrows(InvalidProductException.class, () -> p.changeIngredientsText("x\u0000"));
        assertNull(p.getIngredientsText(), "a refused change leaves the value as it was");
    }

    @Test
    void imageReferenceCanBeSetReplacedAndClearedWithoutTouchingAnythingElse() {
        Product p = product();
        assertNull(p.getImageUrl());
        assertFalse(p.clearImage(), "nothing to clear");
        assertTrue(p.changeImageUrl("https://images.example/a.png"));
        assertFalse(p.changeImageUrl("https://images.example/a.png"));
        assertTrue(p.changeImageUrl("https://images.example/b.png"));
        assertEquals(new BigDecimal("12.5000"), p.getBasePrice());
        assertEquals("Grilled", p.getDescription());
        assertTrue(p.isActive());
        assertFalse(p.isPublished());
        assertTrue(p.clearImage());
        assertNull(p.getImageUrl());
    }

    @Test
    void theEntityStillGuardsTheShapeOfAnImageReference() {
        Product p = product();
        String tooLong = "https://images.example/" + "a".repeat(2030) + ".png";
        for (String bad : new String[] {null, "", "   ", tooLong, "https://images.example/a\u0000.png"}) {
            assertThrows(InvalidProductException.class, () -> p.changeImageUrl(bad));
        }
        assertNull(p.getImageUrl(), "a refused value leaves the field as it was");
    }
    // ---- calories, weight, preparation time ----

    private static Product withNutrition(Integer calories, BigDecimal weight, Integer minutes) {
        return Product.create(RESTAURANT, ACTOR, CATEGORY, new Product.Details("Burger", null, null, BigDecimal.ONE, "TRY",
                BigDecimal.ZERO, true, new Product.Nutrition(calories, weight, minutes)));
    }

    @Test
    void aNewProductHasUnknownNutritionByDefault() {
        Product p = product();
        assertNull(p.getCalories());
        assertNull(p.getWeightGrams());
        assertNull(p.getEstimatedPrepMinutes());
        Product explicit = Product.create(RESTAURANT, ACTOR, CATEGORY, new Product.Details("Burger", null, null, BigDecimal.ONE,
                "TRY", BigDecimal.ZERO, true, null));
        assertNull(explicit.getCalories());
    }

    @Test
    void nutritionIsStoredWithTheCreatedProductAndTheWeightHasThreeDecimals() {
        Product p = withNutrition(250, new BigDecimal("12.5"), 8);
        assertEquals(250, p.getCalories());
        assertEquals(new BigDecimal("12.500"), p.getWeightGrams());
        assertEquals(8, p.getEstimatedPrepMinutes());
    }

    @Test
    void caloriesBoundaries() {
        assertNull(Product.normalizeCalories(null));
        assertEquals(0, Product.normalizeCalories(0), "zero is a valid, known value");
        assertEquals(10_000, Product.normalizeCalories(10_000));
        assertThrows(InvalidProductException.class, () -> Product.normalizeCalories(-1));
        assertThrows(InvalidProductException.class, () -> Product.normalizeCalories(10_001));
        assertThrows(InvalidProductException.class, () -> Product.normalizeCalories(Integer.MAX_VALUE));
        assertThrows(InvalidProductException.class, () -> Product.normalizeCalories(Integer.MIN_VALUE));
    }

    @Test
    void preparationTimeBoundaries() {
        assertNull(Product.normalizeEstimatedPrepMinutes(null));
        assertEquals(0, Product.normalizeEstimatedPrepMinutes(0));
        assertEquals(240, Product.normalizeEstimatedPrepMinutes(240));
        assertThrows(InvalidProductException.class, () -> Product.normalizeEstimatedPrepMinutes(-1));
        assertThrows(InvalidProductException.class, () -> Product.normalizeEstimatedPrepMinutes(241));
    }

    @Test
    void weightBoundariesAndExactDecimals() {
        assertNull(Product.normalizeWeightGrams(null));
        assertEquals(new BigDecimal("0.001"), Product.normalizeWeightGrams(new BigDecimal("0.001")));
        assertEquals(new BigDecimal("10000.000"), Product.normalizeWeightGrams(new BigDecimal("10000")));
        assertEquals(new BigDecimal("10000.000"), Product.normalizeWeightGrams(new BigDecimal("10000.000")));
        assertEquals(new BigDecimal("12.345"), Product.normalizeWeightGrams(new BigDecimal("12.345")));
        assertEquals(new BigDecimal("250.000"), Product.normalizeWeightGrams(new BigDecimal("2.5E+2")));
        for (String bad : new String[] {"0", "0.0", "0.000", "-0.001", "-1", "10000.001", "10001", "1E+4001"}) {
            BigDecimal weight = new BigDecimal(bad);
            assertThrows(InvalidProductException.class, () -> Product.normalizeWeightGrams(weight), bad);
        }
    }

    @Test
    void extraDecimalDigitsAreRefusedNeverRoundedOrCut() {
        for (String bad : new String[] {"12.3456", "12.3450", "0.0005", "1.0000", "250.0000", "0.0001", "1E-10"}) {
            BigDecimal weight = new BigDecimal(bad);
            assertThrows(InvalidProductException.class, () -> Product.normalizeWeightGrams(weight), bad);
        }
    }

    @Test
    void anAbsurdExponentIsRefusedWithoutBeingExpanded() {
        BigDecimal hugeExponent = new BigDecimal("1E+999999999");
        BigDecimal tinyExponent = new BigDecimal("1E-999999999");
        assertThrows(InvalidProductException.class, () -> Product.normalizeWeightGrams(hugeExponent));
        assertThrows(InvalidProductException.class, () -> Product.normalizeWeightGrams(tinyExponent));
    }

    @Test
    void creatingWithAnInvalidNutritionValueIsRejected() {
        assertThrows(InvalidProductException.class, () -> withNutrition(-1, null, null));
        assertThrows(InvalidProductException.class, () -> withNutrition(null, BigDecimal.ZERO, null));
        assertThrows(InvalidProductException.class, () -> withNutrition(null, null, 241));
    }

    @Test
    void nutritionMutatorsReportARealChangeOnly() {
        Product p = product();
        assertTrue(p.changeCalories(100));
        assertFalse(p.changeCalories(100), "same value is a no-op");
        assertTrue(p.changeCalories(null), "null clears");
        assertFalse(p.changeCalories(null), "clearing an unknown value is a no-op");
        assertNull(p.getCalories());

        assertTrue(p.changeWeightGrams(new BigDecimal("12.5")));
        assertFalse(p.changeWeightGrams(new BigDecimal("12.500")), "12.5 and 12.500 are the same weight");
        assertFalse(p.changeWeightGrams(new BigDecimal("1.25E+1")));
        assertTrue(p.changeWeightGrams(new BigDecimal("12.501")));
        assertTrue(p.changeWeightGrams(null));
        assertFalse(p.changeWeightGrams(null));
        assertNull(p.getWeightGrams());

        assertTrue(p.changeEstimatedPrepMinutes(0), "zero is a value, different from unknown");
        assertFalse(p.changeEstimatedPrepMinutes(0));
        assertTrue(p.changeEstimatedPrepMinutes(null));
        assertFalse(p.changeEstimatedPrepMinutes(null));
    }

    @Test
    void zeroAndUnknownAreDifferentStates() {
        Product p = withNutrition(0, null, 0);
        assertEquals(0, p.getCalories());
        assertNull(p.getWeightGrams());
        assertEquals(0, p.getEstimatedPrepMinutes());
        assertTrue(p.changeCalories(null));
        assertNull(p.getCalories());
    }

    @Test
    void aRefusedNutritionValueLeavesTheFieldAsItWas() {
        Product p = withNutrition(100, new BigDecimal("10"), 5);
        assertThrows(InvalidProductException.class, () -> p.changeCalories(10_001));
        BigDecimal tooPrecise = new BigDecimal("1.2345");
        assertThrows(InvalidProductException.class, () -> p.changeWeightGrams(tooPrecise));
        assertThrows(InvalidProductException.class, () -> p.changeEstimatedPrepMinutes(-1));
        assertEquals(100, p.getCalories());
        assertEquals(new BigDecimal("10.000"), p.getWeightGrams());
        assertEquals(5, p.getEstimatedPrepMinutes());
    }

    @Test
    void nutritionChangesLeaveEveryOtherFieldAlone() {
        Product p = product();
        p.changeCalories(5);
        p.changeWeightGrams(new BigDecimal("1"));
        p.changeEstimatedPrepMinutes(1);
        assertEquals("Burger", p.getName());
        assertEquals("Grilled", p.getDescription());
        assertEquals(new BigDecimal("12.5000"), p.getBasePrice());
        assertTrue(p.isActive());
        assertFalse(p.isPublished());
    }
}
