package com.restofy.menu.application.service;

import java.math.BigDecimal;

/**
 * The rating summary of one product as the customer menu shows it. Menu never computes, stores or invents it: it
 * comes from the Rating service through {@link RatingSummaryPort}, and until that service exists the answer is always
 * {@link Status#UNAVAILABLE}. Zero never stands for "unknown": no rating at all is {@link Status#NOT_RATED} (count 0,
 * average absent) and "cannot say" is {@link Status#UNAVAILABLE} (both absent).
 *
 * <p>The scale of {@value #MIN_SCORE}..{@value #MAX_SCORE} is a provisional Menu assumption that has to be confirmed
 * against the real Rating service when it exists.
 */
public record RatingSummary(Status status, BigDecimal averageRating, Long ratingCount) {

    public static final int MIN_SCORE = 1;
    public static final int MAX_SCORE = 5;
    private static final RatingSummary UNAVAILABLE = new RatingSummary(Status.UNAVAILABLE, null, null);
    private static final RatingSummary NOT_RATED = new RatingSummary(Status.NOT_RATED, null, 0L);

    public enum Status {
        /** A valid average and at least one rating, from the authoritative source. */
        RATED,
        /** The source was reached and the product has no ratings. */
        NOT_RATED,
        /** The source cannot be reached, does not exist yet, or answered something invalid. */
        UNAVAILABLE
    }

    public static RatingSummary unavailable() {
        return UNAVAILABLE;
    }

    public static RatingSummary notRated() {
        return NOT_RATED;
    }

    public static RatingSummary rated(BigDecimal average, long count) {
        return new RatingSummary(Status.RATED, average, count);
    }

    /**
     * What may be shown to a customer: {@code candidate} when it is internally consistent, otherwise
     * {@link #unavailable()}. A null answer, a negative or zero count for RATED, an average outside the scale, a
     * NOT_RATED with values, or an UNAVAILABLE with values is never passed on as if it were real data.
     */
    public static RatingSummary validated(RatingSummary candidate) {
        if (candidate == null || candidate.status() == null) {
            return UNAVAILABLE;
        }
        return switch (candidate.status()) {
            case RATED -> isValidRated(candidate) ? candidate : UNAVAILABLE;
            case NOT_RATED -> candidate.averageRating() == null && candidate.ratingCount() != null
                    && candidate.ratingCount() == 0 ? NOT_RATED : UNAVAILABLE;
            case UNAVAILABLE -> UNAVAILABLE;
        };
    }

    private static boolean isValidRated(RatingSummary candidate) {
        return candidate.ratingCount() != null && candidate.ratingCount() >= 1 && candidate.averageRating() != null
                && candidate.averageRating().compareTo(BigDecimal.valueOf(MIN_SCORE)) >= 0
                && candidate.averageRating().compareTo(BigDecimal.valueOf(MAX_SCORE)) <= 0;
    }
}
