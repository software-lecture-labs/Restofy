package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.enums.ClosureBlocker;
import com.restofy.waiter.domain.enums.DeliveryStatus;
import com.restofy.waiter.domain.enums.OrderBusinessStatus;
import com.restofy.waiter.domain.enums.PaymentSummaryStatus;
import com.restofy.waiter.domain.enums.PreparationStatus;
import com.restofy.waiter.domain.enums.TableSessionStatus;
import com.restofy.waiter.infrastructure.client.order.OrderDto;
import com.restofy.waiter.infrastructure.client.order.SettlementDto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The informative closure matrix. It looks only at what the
 * owners report: QR's session, and per order Order's delivery and cancellation state, Kitchen's readiness
 * projection and Order's verified money facts (derived from Payment's records). Nothing the client claims
 * ("paid", "remaining amount is zero") is an input, and there is no override: a blocker is a blocker.
 *
 * <p>The result is a view, not a decision. The decision is taken by QR at the moment of closing, with Order and
 * Payment, atomically with the stop of new order acceptance; this matrix can be stale a moment later and cannot
 * see orders that are still being accepted.
 *
 * <p>Rules per order, in this order of meaning:
 * <ul>
 *   <li>Not cancelled, not served: {@code ORDER_IN_PREPARATION} unless Kitchen marked it ready, then
 *       {@code ORDER_AWAITING_SERVICE}. A paid order is not different: prepayment never closes a table on its own.</li>
 *   <li>Payment projection or settlement missing: {@code SETTLEMENT_UNVERIFIED}.</li>
 *   <li>Payment {@code UNKNOWN}: {@code PAYMENT_OUTCOME_UNKNOWN}; {@code PENDING}: {@code PAYMENT_IN_PROGRESS}.</li>
 *   <li>Debt still due: {@code UNPAID_BALANCE} (Order's figure, whatever the payment summary says).</li>
 *   <li>Refund required by cancellations and not confirmed as made: {@code REFUND_PENDING}.</li>
 *   <li>No debt on an order that is worth money while the payment summary says it was never paid:
 *       {@code SETTLEMENT_UNVERIFIED} (the facts contradict each other).</li>
 * </ul>
 * Fully cancelled orders need no service, but their money facts count: a refund can still be owed.
 */
public final class ClosureMatrix {

    /** More orders than this are not assessed (the owners still decide at closing). */
    public static final int MAX_ORDERS = 500;

    /** One blocker, with the order it belongs to (null for session-level blockers). */
    public record Finding(ClosureBlocker blocker, UUID orderId, String orderNumber) {
    }

    private ClosureMatrix() {
    }

    /** Blockers of a table without a live session, or whose session is not open. */
    public static List<Finding> forSession(TableSessionStatus session) {
        if (session == null) return List.of(new Finding(ClosureBlocker.NO_LIVE_SESSION, null, null));
        if (session != TableSessionStatus.OPEN) return List.of(new Finding(ClosureBlocker.SESSION_CLOSING, null, null));
        return List.of();
    }

    public static List<Finding> forOrders(List<OrderDto> orders) {
        List<Finding> findings = new ArrayList<>();
        for (OrderDto order : orders) {
            for (ClosureBlocker blocker : forOrder(order)) {
                findings.add(new Finding(blocker, order.id(), order.orderNumber()));
            }
        }
        return findings;
    }

    static List<ClosureBlocker> forOrder(OrderDto order) {
        List<ClosureBlocker> blockers = new ArrayList<>();
        boolean cancelled = order.businessStatus() == OrderBusinessStatus.CANCELLED;
        boolean done = order.deliveryStatus() == DeliveryStatus.SERVED || order.businessStatus() == OrderBusinessStatus.COMPLETED;
        if (!cancelled && !done) {
            boolean ready = order.preparation() != null && order.preparation().status() == PreparationStatus.READY;
            blockers.add(ready ? ClosureBlocker.ORDER_AWAITING_SERVICE : ClosureBlocker.ORDER_IN_PREPARATION);
        }
        moneyBlockers(order, cancelled, blockers);
        return blockers;
    }

    private static void moneyBlockers(OrderDto order, boolean cancelled, List<ClosureBlocker> blockers) {
        SettlementDto settlement = order.settlement();
        if (order.payment() == null || settlement == null) {
            blockers.add(ClosureBlocker.SETTLEMENT_UNVERIFIED);
            return;
        }
        PaymentSummaryStatus payment = order.payment().status();
        if (payment == PaymentSummaryStatus.UNKNOWN) blockers.add(ClosureBlocker.PAYMENT_OUTCOME_UNKNOWN);
        if (payment == PaymentSummaryStatus.PENDING) blockers.add(ClosureBlocker.PAYMENT_IN_PROGRESS);
        if (settlement.outstandingDue().signum() > 0) blockers.add(ClosureBlocker.UNPAID_BALANCE);
        if (settlement.refundRequired().compareTo(settlement.refunded()) > 0) blockers.add(ClosureBlocker.REFUND_PENDING);
        boolean worthMoney = !cancelled && order.payableTotal().compareTo(BigDecimal.ZERO) > 0;
        boolean noDebt = settlement.outstandingDue().signum() == 0;
        if (worthMoney && noDebt && payment == PaymentSummaryStatus.NOT_PAID) {
            blockers.add(ClosureBlocker.SETTLEMENT_UNVERIFIED);
        }
    }
}
