package com.restofy.menu.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The conversion is exact or it refuses: it never rounds and never guesses. */
class CatalogMoneyTests {

    @ParameterizedTest
    @CsvSource({"0,TRY,0", "0.0000,TRY,0", "12.5,TRY,1250", "12.50,TRY,1250", "12.5000,TRY,1250", "100,TRY,10000",
            "100,JPY,100", "1.234,KWD,1234", "0.01,USD,1", "10000000000,TRY,1000000000000"})
    void anExactAmountBecomesMinorUnits(String amount, String currency, long minor) {
        var result = CatalogMoney.toMinor(new BigDecimal(amount), currency);
        assertEquals(minor, result.minor());
        assertNull(result.issue());
    }

    @ParameterizedTest
    @CsvSource({"12.345,TRY", "0.0001,TRY", "100.5,JPY", "1.2345,KWD"})
    void anAmountFinerThanTheMinorUnitIsRefusedNotRounded(String amount, String currency) {
        var result = CatalogMoney.toMinor(new BigDecimal(amount), currency);
        assertNull(result.minor());
        assertEquals(PriceIssue.PRECISION_EXCEEDS_CURRENCY, result.issue());
    }

    @ParameterizedTest
    @CsvSource({"XXX", "XAU", "ZZZ"})
    void aCurrencyWithoutAMinorUnitOrUnknownIsRefused(String currency) {
        var result = CatalogMoney.toMinor(BigDecimal.TEN, currency);
        assertNull(result.minor());
        assertEquals(PriceIssue.UNSUPPORTED_CURRENCY, result.issue());
    }

    @ParameterizedTest
    @CsvSource({"10000000000.01,TRY", "999999999999999.99,TRY", "100000000000000,TRY"})
    void anAmountBeyondTheOrderRangeIsRefused(String amount, String currency) {
        var result = CatalogMoney.toMinor(new BigDecimal(amount), currency);
        assertNull(result.minor());
        assertEquals(PriceIssue.AMOUNT_OUT_OF_RANGE, result.issue());
    }

    @Test
    void aNegativeAmountIsRefused() {
        var result = CatalogMoney.toMinor(new BigDecimal("-1"), "TRY");
        assertNull(result.minor());
        assertEquals(PriceIssue.AMOUNT_OUT_OF_RANGE, result.issue());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0.0000,0", "10,1000", "10.0000,1000", "8.25,825", "18,1800", "100,10000", "0.01,1"})
    void aWholeNumberOfBasisPointsIsConverted(String percent, int bps) {
        assertEquals(bps, CatalogMoney.toBasisPoints(new BigDecimal(percent)));
    }

    @ParameterizedTest
    @CsvSource({"8.125", "0.0001", "12.3456", "100.01", "-1"})
    void aFractionalOrOutOfRangeRateHasNoBasisPoints(String percent) {
        assertNull(CatalogMoney.toBasisPoints(new BigDecimal(percent)));
    }
}
