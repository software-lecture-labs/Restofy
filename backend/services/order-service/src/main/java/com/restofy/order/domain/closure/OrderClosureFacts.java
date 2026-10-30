package com.restofy.order.domain.closure;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * What the Order Service knows about one order of a table session, reduced to what closing needs. Money is the
 * Payment Service's verified word as recorded here ({@code payment}, {@code dueMinor}); nothing comes from a client.
 *
 * @param outstandingRefundMinor money that cancellations require to be refunded and the Payment Service has not confirmed
 * @param pendingCapture         a trusted PAID waits for its PENDING (not verified yet)
 * @param reconciliationCase     reported money was recorded that could not be applied to this order
 */
public record OrderClosureFacts(UUID orderId, FulfillmentStatus fulfillment, PaymentStatus payment, long dueMinor,
                                boolean openCancellation, long outstandingRefundMinor, boolean pendingCapture,
                                boolean reconciliationCase) {

    public OrderClosureFacts {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(fulfillment, "fulfillment");
        Objects.requireNonNull(payment, "payment");
    }
}
