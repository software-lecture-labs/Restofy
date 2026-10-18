package com.restofy.waiter.infrastructure.client.order;

import java.math.BigDecimal;

import static com.restofy.waiter.infrastructure.client.Fields.required;

/**
 * DRAFT Order contract v0.4: the money facts of one order that matter for closing its table, in major units of
 * the order's currency. Order derives them from verified payment records (Payment is the authority of
 * collections); a value the client sent is never among them. {@code collected} is what was verifiably collected,
 * {@code outstandingDue} the debt that remains, {@code refundRequired} the refund that applied cancellations
 * require and {@code refunded} the refund that was confirmed as made. A required refund that is not yet made is
 * an unfinished reconciliation.
 */
public record SettlementDto(BigDecimal collected, BigDecimal outstandingDue, BigDecimal refundRequired,
                            BigDecimal refunded) {

    public SettlementDto {
        nonNegative(required(collected, "settlement.collected"), "settlement.collected");
        nonNegative(required(outstandingDue, "settlement.outstandingDue"), "settlement.outstandingDue");
        nonNegative(required(refundRequired, "settlement.refundRequired"), "settlement.refundRequired");
        nonNegative(required(refunded, "settlement.refunded"), "settlement.refunded");
    }

    private static void nonNegative(BigDecimal value, String name) {
        if (value.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
    }
}
