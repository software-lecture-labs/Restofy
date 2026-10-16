package com.restofy.order.domain.pricing;

import com.restofy.order.domain.pricing.PriceCalculator.LinePrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PriceCalculatorTests {

    @Test
    void pricesALineWithQuantityAndVat() {
        LinePrice price = PriceCalculator.priceLine(25_000L, 2, 1_000);
        assertEquals(25_000L, price.unitGrossMinor());
        assertEquals(50_000L, price.lineGrossMinor());
        assertEquals(45_455L, price.lineNetMinor());
        assertEquals(4_545L, price.lineVatMinor());
    }

    @ParameterizedTest(name = "gross {0} at {1} bps -> net {2}, vat {3}")
    @CsvSource({
            "0,    1000, 0,  0",
            "1,    1000, 1,  0",     // 0.909 rounds up
            "5,    1000, 5,  0",     // 4.545 rounds up
            "11,   1000, 10, 1",     // exact
            "3,    10000, 2, 1",     // exactly 1.5 -> half rounds up
            "100,  0,    100, 0",    // 0% VAT
            "118,  1800, 100, 18",
            "12000, 2000, 10000, 2000",
            "9999, 800,  9258, 741"  // 9258.33 rounds down
    })
    void roundsHalfUpPerLine(long gross, int bps, long expectedNet, long expectedVat) {
        LinePrice price = PriceCalculator.priceLine(gross, 1, bps);
        assertEquals(expectedNet, price.lineNetMinor());
        assertEquals(expectedVat, price.lineVatMinor());
    }

    @Test
    void netPlusVatAlwaysEqualsGross() {
        for (int rate : new int[]{0, 100, 800, 1_000, 1_800, 2_000, 10_000}) {
            for (long unit = 0; unit <= 3_000; unit++) {
                for (int quantity : new int[]{1, 3, 7}) {
                    LinePrice price = PriceCalculator.priceLine(unit, quantity, rate);
                    assertEquals(price.lineGrossMinor(), price.lineNetMinor() + price.lineVatMinor(),
                            "unit=" + unit + " qty=" + quantity + " rate=" + rate);
                }
            }
        }
    }

    @Test
    void unknownTaxRateLeavesNetAndVatUnset() {
        LinePrice price = PriceCalculator.priceLine(1_000L, 3, null);
        assertEquals(3_000L, price.lineGrossMinor());
        assertNull(price.lineNetMinor());
        assertNull(price.lineVatMinor());
    }

    @Test
    void differentRatesAreRoundedPerLineAndThenSummed() {
        LinePrice tenPercent = PriceCalculator.priceLine(1_005L, 1, 1_000);   // net 914 vat 91
        LinePrice twentyPercent = PriceCalculator.priceLine(1_005L, 1, 2_000); // net 838 vat 167
        assertEquals(914L, tenPercent.lineNetMinor());
        assertEquals(838L, twentyPercent.lineNetMinor());
        long gross = tenPercent.lineGrossMinor() + twentyPercent.lineGrossMinor();
        long net = tenPercent.lineNetMinor() + twentyPercent.lineNetMinor();
        long vat = tenPercent.lineVatMinor() + twentyPercent.lineVatMinor();
        assertEquals(gross, net + vat);
    }

    @Test
    void addsExtrasToTheUnitPrice() {
        assertEquals(34_000L, PriceCalculator.unitGross(25_000L, 9_000L));
    }

    @Test
    void overflowIsDetectedInsteadOfWrappingAround() {
        assertThrows(ArithmeticException.class, () -> PriceCalculator.priceLine(Long.MAX_VALUE / 2, 3, 1_000));
        assertThrows(ArithmeticException.class, () -> PriceCalculator.priceLine(Long.MAX_VALUE / 5, 2, 1_000));
        assertThrows(ArithmeticException.class, () -> PriceCalculator.unitGross(Long.MAX_VALUE, 1L));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.priceLine(-1L, 1, 1_000));
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.priceLine(100L, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.priceLine(100L, -2, 1_000));
        assertThrows(IllegalArgumentException.class, () -> PriceCalculator.netOfGross(100L, -1));
    }
}
