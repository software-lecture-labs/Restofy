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
}
