package com.restofy.waiter.application.service;

import com.restofy.waiter.domain.enums.ClosureBlocker;
import com.restofy.waiter.domain.enums.DeliveryStatus;
import com.restofy.waiter.domain.enums.OrderBusinessStatus;
import com.restofy.waiter.domain.enums.PaymentSummaryStatus;
import com.restofy.waiter.domain.enums.PreparationStatus;
import com.restofy.waiter.domain.enums.TableSessionStatus;
import com.restofy.waiter.infrastructure.client.order.OrderDto;
import com.restofy.waiter.infrastructure.client.order.OrderItemDto;
import com.restofy.waiter.infrastructure.client.order.PaymentProjectionDto;
import com.restofy.waiter.infrastructure.client.order.PreparationProjectionDto;
import com.restofy.waiter.infrastructure.client.order.SettlementDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closure matrix as a pure function: one case per row of the documented matrix. The
 * matrix reads only what the owners report, so a test builds the owners' answers and never a client claim.
 */
class ClosureMatrixTests {

    private static final Instant AT = Instant.parse("2026-10-16T10:00:00Z");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static SettlementDto settled(String collected) {
        return new SettlementDto(new BigDecimal(collected), ZERO, ZERO, ZERO);
    }

    private static SettlementDto owing(String due) {
        return new SettlementDto(ZERO, new BigDecimal(due), ZERO, ZERO);
    }

    /** Builds an order the way Order would report it; {@code preparation} and {@code payment} may be null (absent). */
    private static OrderDto order(OrderBusinessStatus business, DeliveryStatus delivery, PreparationStatus preparation,
                                  PaymentSummaryStatus payment, SettlementDto settlement, String payable) {
        BigDecimal total = new BigDecimal(payable);
        OrderItemDto item = new OrderItemDto(UUID.randomUUID(), UUID.randomUUID(), "Izgara Köfte", 1,
                business == OrderBusinessStatus.CANCELLED ? 1 : 0, total, total, null, List.of());
        return new OrderDto(UUID.randomUUID(), UUID.randomUUID(), "A-1", UUID.randomUUID(), UUID.randomUUID(), business,
                delivery, AT, delivery == DeliveryStatus.SERVED ? AT : null,
                preparation == null ? null : new PreparationProjectionDto(preparation, AT,
                        preparation == PreparationStatus.READY ? AT : null, 1, AT),
                payment == null ? null : new PaymentProjectionDto(payment, 1, AT), settlement, "TRY", total,
                business == OrderBusinessStatus.CANCELLED ? total : ZERO,
                business == OrderBusinessStatus.CANCELLED ? ZERO : total, List.of(item), List.of(), 4);
    }

    private static List<ClosureBlocker> blockers(OrderDto order) {
        return ClosureMatrix.forOrder(order);
    }

    // ----- the allowed rows -----

    @Test
    void servedAndVerifiablyPaidOrderBlocksNothing() {
        assertEquals(List.of(), blockers(order(OrderBusinessStatus.ACCEPTED, DeliveryStatus.SERVED, PreparationStatus.READY,
                PaymentSummaryStatus.PAID, settled("150.00"), "150.00")));
    }

    @Test
    void fullyCancelledOrderWithNothingCollectedBlocksNothing() {
        assertEquals(List.of(), blockers(order(OrderBusinessStatus.CANCELLED, DeliveryStatus.PENDING, PreparationStatus.CANCELLED,
                PaymentSummaryStatus.NOT_PAID, owing("0"), "150.00")));
    }

    @Test
    void partiallyCancelledServedOrderThatIsPaidBlocksNothing() {
        assertEquals(List.of(), blockers(order(OrderBusinessStatus.PARTIALLY_CANCELLED, DeliveryStatus.SERVED,
                PreparationStatus.READY, PaymentSummaryStatus.PAID, settled("75.00"), "75.00")));
    }

    @Test
    void completedOrderCountsAsDelivered() {
        assertEquals(List.of(), blockers(order(OrderBusinessStatus.COMPLETED, DeliveryStatus.PENDING, PreparationStatus.READY,
                PaymentSummaryStatus.PAID, settled("150.00"), "150.00")));
    }

    @Test
    void cancelledOrderWithRefundAlreadyMadeBlocksNothing() {
        SettlementDto refunded = new SettlementDto(new BigDecimal("150.00"), ZERO, new BigDecimal("150.00"), new BigDecimal("150.00"));
        assertEquals(List.of(), blockers(order(OrderBusinessStatus.CANCELLED, DeliveryStatus.PENDING, PreparationStatus.CANCELLED,
                PaymentSummaryStatus.PAID, refunded, "150.00")));
    }

    // ----- delivery: payment never closes a table on its own -----

    @Test
    void prepaidOrderStillInPreparationBlocksClosing() {
        for (PreparationStatus status : List.of(PreparationStatus.WAITING, PreparationStatus.PREPARING)) {
            assertEquals(List.of(ClosureBlocker.ORDER_IN_PREPARATION), blockers(order(OrderBusinessStatus.ACCEPTED,
                    DeliveryStatus.PENDING, status, PaymentSummaryStatus.PAID, settled("150.00"), "150.00")),
                    "A paid order that kitchen is still working on must keep the table open: " + status);
        }
    }

    @Test
    void prepaidOrderWhoseKitchenRecordHasNotArrivedIsNotDelivered() {
        assertEquals(List.of(ClosureBlocker.ORDER_IN_PREPARATION), blockers(order(OrderBusinessStatus.ACCEPTED,
                DeliveryStatus.PENDING, null, PaymentSummaryStatus.PAID, settled("150.00"), "150.00")));
    }

    @Test
    void prepaidReadyOrderThatWasNotServedYetBlocksClosing() {
        assertEquals(List.of(ClosureBlocker.ORDER_AWAITING_SERVICE), blockers(order(OrderBusinessStatus.ACCEPTED,
                DeliveryStatus.PENDING, PreparationStatus.READY, PaymentSummaryStatus.PAID, settled("150.00"), "150.00")));
    }

    @Test
    void partiallyCancelledOrderStillNeedsItsRemainingServiceBeforeClosing() {
        assertEquals(List.of(ClosureBlocker.ORDER_AWAITING_SERVICE), blockers(order(OrderBusinessStatus.PARTIALLY_CANCELLED,
                DeliveryStatus.PENDING, PreparationStatus.READY, PaymentSummaryStatus.PAID, settled("75.00"), "75.00")));
    }

    // ----- money -----

    @Test
    void unpaidBalanceBlocksClosingAndIsOrdersFigureNotThePaymentSummary() {
        assertEquals(List.of(ClosureBlocker.UNPAID_BALANCE), blockers(order(OrderBusinessStatus.ACCEPTED, DeliveryStatus.SERVED,
                PreparationStatus.READY, PaymentSummaryStatus.NOT_PAID, owing("150.00"), "150.00")));
        // A summary that says PAID cannot hide a debt that Order still reports.
        assertEquals(List.of(ClosureBlocker.UNPAID_BALANCE), blockers(order(OrderBusinessStatus.ACCEPTED, DeliveryStatus.SERVED,
                PreparationStatus.READY, PaymentSummaryStatus.PAID, owing("40.00"), "150.00")));
    }

    @Test
    void paymentInProgressBlocksClosing() {
        List<ClosureBlocker> found = blockers(order(OrderBusinessStatus.ACCEPTED, DeliveryStatus.SERVED, PreparationStatus.READY,
                PaymentSummaryStatus.PENDING, owing("150.00"), "150.00"));
        assertTrue(found.contains(ClosureBlocker.PAYMENT_IN_PROGRESS));
    }

    @Test
    void paymentWhoseResultIsUnknownBlocksClosingEvenWithoutADebt() {
        assertEquals(List.of(ClosureBlocker.PAYMENT_OUTCOME_UNKNOWN), blockers(order(OrderBusinessStatus.ACCEPTED,
                DeliveryStatus.SERVED, PreparationStatus.READY, PaymentSummaryStatus.UNKNOWN, settled("150.00"), "150.00")));
    }

    @Test
    void refundRequiredButNotMadeBlocksClosing() {
        SettlementDto owedRefund = new SettlementDto(new BigDecimal("150.00"), ZERO, new BigDecimal("75.00"), ZERO);
        assertEquals(List.of(ClosureBlocker.REFUND_PENDING), blockers(order(OrderBusinessStatus.PARTIALLY_CANCELLED,
                DeliveryStatus.SERVED, PreparationStatus.READY, PaymentSummaryStatus.PAID, owedRefund, "75.00")));
        SettlementDto partlyRefunded = new SettlementDto(new BigDecimal("150.00"), ZERO, new BigDecimal("75.00"), new BigDecimal("50.00"));
        assertEquals(List.of(ClosureBlocker.REFUND_PENDING), blockers(order(OrderBusinessStatus.PARTIALLY_CANCELLED,
                DeliveryStatus.SERVED, PreparationStatus.READY, PaymentSummaryStatus.PAID, partlyRefunded, "75.00")));
    }

    @Test
    void cancelledOrderWithAnUnmadeRefundStillBlocksClosing() {
        SettlementDto owedRefund = new SettlementDto(new BigDecimal("150.00"), ZERO, new BigDecimal("150.00"), ZERO);
        assertEquals(List.of(ClosureBlocker.REFUND_PENDING), blockers(order(OrderBusinessStatus.CANCELLED,
                DeliveryStatus.PENDING, PreparationStatus.CANCELLED, PaymentSummaryStatus.PAID, owedRefund, "150.00")));
    }

    @Test
    void missingPaymentFactsAreUnverifiedNeverAssumedPaid() {
        assertEquals(List.of(ClosureBlocker.SETTLEMENT_UNVERIFIED), blockers(order(OrderBusinessStatus.ACCEPTED,
                DeliveryStatus.SERVED, PreparationStatus.READY, PaymentSummaryStatus.PAID, null, "150.00")));
        assertEquals(List.of(ClosureBlocker.SETTLEMENT_UNVERIFIED), blockers(order(OrderBusinessStatus.ACCEPTED,
                DeliveryStatus.SERVED, PreparationStatus.READY, null, settled("150.00"), "150.00")));
        assertEquals(List.of(ClosureBlocker.SETTLEMENT_UNVERIFIED), blockers(order(OrderBusinessStatus.CANCELLED,
                DeliveryStatus.PENDING, PreparationStatus.CANCELLED, null, null, "150.00")));
    }

    @Test
    void factsThatContradictEachOtherAreUnverified() {
        // No debt although the summary says the order was never paid.
        assertEquals(List.of(ClosureBlocker.SETTLEMENT_UNVERIFIED), blockers(order(OrderBusinessStatus.ACCEPTED,
                DeliveryStatus.SERVED, PreparationStatus.READY, PaymentSummaryStatus.NOT_PAID, settled("0"), "150.00")));
    }

    @Test
    void severalProblemsOfOneOrderAreAllReported() {
        List<ClosureBlocker> found = blockers(order(OrderBusinessStatus.ACCEPTED, DeliveryStatus.PENDING, PreparationStatus.PREPARING,
                PaymentSummaryStatus.UNKNOWN, owing("150.00"), "150.00"));
        assertEquals(List.of(ClosureBlocker.ORDER_IN_PREPARATION, ClosureBlocker.PAYMENT_OUTCOME_UNKNOWN,
                ClosureBlocker.UNPAID_BALANCE), found);
    }

    // ----- sessions and lists -----

    @Test
    void sessionLevelBlockers() {
        assertEquals(List.of(), ClosureMatrix.forSession(TableSessionStatus.OPEN));
        assertEquals(ClosureBlocker.NO_LIVE_SESSION, ClosureMatrix.forSession(null).getFirst().blocker());
        assertEquals(ClosureBlocker.SESSION_CLOSING, ClosureMatrix.forSession(TableSessionStatus.CLOSING).getFirst().blocker());
    }

    @Test
    void findingsCarryTheOrderTheyBelongTo() {
        OrderDto blocked = order(OrderBusinessStatus.ACCEPTED, DeliveryStatus.PENDING, PreparationStatus.PREPARING,
                PaymentSummaryStatus.PAID, settled("150.00"), "150.00");
        OrderDto fine = order(OrderBusinessStatus.ACCEPTED, DeliveryStatus.SERVED, PreparationStatus.READY,
                PaymentSummaryStatus.PAID, settled("150.00"), "150.00");
        List<ClosureMatrix.Finding> findings = ClosureMatrix.forOrders(List.of(fine, blocked));
        assertEquals(1, findings.size());
        assertEquals(blocked.id(), findings.getFirst().orderId());
        assertEquals("A-1", findings.getFirst().orderNumber());
    }

    @Test
    void anEmptySessionHasNothingThatBlocksIt() {
        assertEquals(List.of(), ClosureMatrix.forOrders(List.of()));
    }

    // ----- Order's settlement facts are checked on arrival -----

    @Test
    void settlementFactsMustBeCompleteAndNotNegative() {
        assertThrows(IllegalArgumentException.class, () -> new SettlementDto(null, ZERO, ZERO, ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SettlementDto(ZERO, new BigDecimal("-1"), ZERO, ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SettlementDto(ZERO, ZERO, ZERO, new BigDecimal("-0.01")));
    }
}
