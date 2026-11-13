package com.restofy.menu.application.service;

import com.restofy.menu.application.service.RatingSummary.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class RatingSummaryTests {

    private static RatingSummary of(Status status, String average, Long count) {
        return new RatingSummary(status, average == null ? null : new BigDecimal(average), count);
    }

    @Test
    void theThreeFactoriesFollowTheContract() {
        assertEquals(Status.UNAVAILABLE, RatingSummary.unavailable().status());
        assertNull(RatingSummary.unavailable().averageRating());
        assertNull(RatingSummary.unavailable().ratingCount(), "unavailable is not a count of zero");
        assertEquals(Status.NOT_RATED, RatingSummary.notRated().status());
        assertNull(RatingSummary.notRated().averageRating());
        assertEquals(0L, RatingSummary.notRated().ratingCount());
        var rated = RatingSummary.rated(new BigDecimal("4.5"), 12);
        assertEquals(Status.RATED, rated.status());
        assertEquals(12L, rated.ratingCount());
    }

    @ParameterizedTest
    @CsvSource({"1", "1.0", "3.75", "4.5", "5", "5.00"})
    void aRatedSummaryInsideTheScaleIsKept(String average) {
        RatingSummary candidate = of(Status.RATED, average, 3L);
        assertSame(candidate, RatingSummary.validated(candidate));
    }

    @ParameterizedTest
    @CsvSource({"0", "0.99", "5.01", "6", "-1", "100"})
    void anAverageOutsideTheScaleIsNeverShown(String average) {
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.RATED, average, 3L)));
    }

    @Test
    void aRatedSummaryMustHaveAtLeastOneRatingAndAnAverage() {
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.RATED, "4", 0L)));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.RATED, "4", -1L)));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.RATED, "4", null)));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.RATED, null, 5L)));
    }

    @Test
    void notRatedMustHaveCountZeroAndNoAverage() {
        assertEquals(RatingSummary.notRated(), RatingSummary.validated(of(Status.NOT_RATED, null, 0L)));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.NOT_RATED, null, 5L)));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.NOT_RATED, "4", 0L)));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.NOT_RATED, null, null)));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.NOT_RATED, null, -1L)));
    }

    @Test
    void unavailableNeverCarriesValuesAndNothingElseIsAccepted() {
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(of(Status.UNAVAILABLE, "4", 3L)));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(null));
        assertEquals(RatingSummary.unavailable(), RatingSummary.validated(new RatingSummary(null, new BigDecimal("4"), 3L)));
    }
}
