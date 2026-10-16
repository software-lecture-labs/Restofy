package com.restofy.order.domain.pricing;

/**
 * Single money engine for the Order Service. Amounts are integer minor units; prices are tax-inclusive.
 * Rounding policy (fixed): per line, VAT-exclusive net = ROUND_HALF_UP(gross * 10000 / (10000 + rateBps)),
 * vat = gross - net, so {@code net + vat == gross} always holds. Order totals are the exact sums of the lines.
 */
public final class PriceCalculator {

    private static final long BPS_BASE = 10_000L;

    private PriceCalculator() {
    }

    /**
     * Prices one line.
     *
     * @param unitGrossMinor tax-inclusive unit price including paid extras
     * @param taxRateBps     VAT rate in basis points, or {@code null} when unknown (net/vat are then {@code null})
     * @throws ArithmeticException if the amount does not fit into a long
     */
    public static LinePrice priceLine(long unitGrossMinor, int quantity, Integer taxRateBps) {
        if (unitGrossMinor < 0 || quantity < 1) {
            throw new IllegalArgumentException("unit price must be >= 0 and quantity >= 1");
        }
        long lineGross = Math.multiplyExact(unitGrossMinor, (long) quantity);
        if (taxRateBps == null) {
            return new LinePrice(unitGrossMinor, lineGross, null, null);
        }
        long net = netOfGross(lineGross, taxRateBps);
        return new LinePrice(unitGrossMinor, lineGross, net, lineGross - net);
    }

    /** Tax-inclusive unit price: base + sum(extra quantity * extra unit delta). */
    public static long unitGross(long basePriceGrossMinor, long extrasUnitDeltaMinor) {
        return Math.addExact(basePriceGrossMinor, extrasUnitDeltaMinor);
    }

    static long netOfGross(long gross, int taxRateBps) {
        if (taxRateBps < 0) {
            throw new IllegalArgumentException("taxRateBps must not be negative");
        }
        long divisor = BPS_BASE + taxRateBps;
        // gross >= 0, so floor((2*num + div) / (2*div)) is ROUND_HALF_UP(num / div).
        long numerator = Math.multiplyExact(gross, BPS_BASE);
        return Math.floorDiv(Math.addExact(Math.multiplyExact(numerator, 2L), divisor), 2L * divisor);
    }

    /** @param lineNetMinor and lineVatMinor are {@code null} when the tax rate is unknown */
    public record LinePrice(long unitGrossMinor, long lineGrossMinor, Long lineNetMinor, Long lineVatMinor) {
    }
}
