package com.restofy.order.domain.cancellation;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;

import java.util.Map;

/**
 * Who may cancel an order in which state, and whether the Kitchen has to confirm. Pure rules, no I/O.
 *
 * <pre>
 *   fulfillment   customer                               waiter / admin
 *   RECEIVED      immediately                            immediately
 *   PREPARING     asks, the Kitchen must confirm         asks, the Kitchen must confirm
 *   READY...      refused (D-02: the product decision    refused
 *   COMPLETED     on cancelling a ready order is open)
 *   CANCELLED     refused (already cancelled)            refused
 *
 *   payment PAID  a customer cannot cancel a paid order; a waiter or admin can, and the refund it requires is recorded
 *                 (never performed or assumed here)
 * </pre>
 * The confirmation can only come from the trusted Kitchen service; without it a request stays open, so a cancellation
 * that touches an order already in preparation is never finalised on an unverified word.
 */
public final class CancellationPolicy {

    public enum Requester { GUEST, WAITER, ADMIN }

    public enum Mode { IMMEDIATE, NEEDS_KITCHEN_CONFIRMATION }

    private CancellationPolicy() {
    }

    /** @throws BusinessException CANCELLATION_NOT_ALLOWED or ORDER_ALREADY_CANCELLED when the request must be refused */
    public static Mode evaluate(Requester requester, FulfillmentStatus fulfillment, PaymentStatus payment) {
        if (fulfillment == FulfillmentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_CANCELLED, "The order is already cancelled");
        }
        if (fulfillment != FulfillmentStatus.RECEIVED && fulfillment != FulfillmentStatus.PREPARING) {
            throw notAllowed("ORDER_ALREADY_PREPARED", fulfillment, payment,
                    "An order that is ready, served or completed can no longer be cancelled");
        }
        if (requester == Requester.GUEST && payment == PaymentStatus.PAID) {
            throw notAllowed("ORDER_ALREADY_PAID", fulfillment, payment,
                    "A paid order cannot be cancelled by the customer; please ask a waiter");
        }
        return fulfillment == FulfillmentStatus.RECEIVED ? Mode.IMMEDIATE : Mode.NEEDS_KITCHEN_CONFIRMATION;
    }

    private static BusinessException notAllowed(String reason, FulfillmentStatus fulfillment, PaymentStatus payment,
                                                String message) {
        return new BusinessException(ErrorCode.CANCELLATION_NOT_ALLOWED, message, Map.of("reason", reason,
                "fulfillmentStatus", fulfillment.name(), "paymentStatus", payment.name()));
    }
}
