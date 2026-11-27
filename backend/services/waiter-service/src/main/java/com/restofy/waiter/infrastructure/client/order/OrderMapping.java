package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.CancellationSource;
import com.restofy.waiter.domain.enums.CancellationStatus;
import com.restofy.waiter.domain.enums.DeliveryStatus;
import com.restofy.waiter.domain.enums.OptionAction;
import com.restofy.waiter.domain.enums.OrderBusinessStatus;
import com.restofy.waiter.domain.enums.PaymentSummaryStatus;
import com.restofy.waiter.domain.enums.PreparationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Turns Order's real answers into Waiter's {@link OrderDto}. The tenant is not part of Order's answers (the
 * caller's restaurant is implied by the staff-authenticated request), so the caller supplies it.
 *
 * <p>Order's fulfillment status carries both the kitchen and the delivery axis of Waiter's model:
 * RECEIVED/PREPARING/READY map to a not yet delivered order, SERVED and COMPLETED to a delivered one. A kitchen
 * READY stays READY after serving (Kitchen's rule), so SERVED and COMPLETED also project to READY. An unknown
 * status is an {@link IllegalArgumentException}, which the client reports as an unusable answer.
 */
final class OrderMapping {

    private OrderMapping() {
    }

    /** A list row omits item and cancellation amounts. */
    static OrderDto fromSummary(UUID restaurantId, OrderWire.Summary row) {
        String currency = row.currency() == null ? "" : row.currency();
        BigDecimal gross = money(row.grossMinor(), currency);
        BigDecimal due = row.currentDueMinor() == null ? gross : money(row.currentDueMinor(), currency);
        return build(restaurantId, row.orderId(), row.orderNo(), row.tableId(), row.tableSessionId(),
                row.fulfillmentStatus(), row.paymentStatus(), currency, gross, BigDecimal.ZERO, due, List.of(),
                List.of(), row.placedAt(), row.placedAt(), row.servedAt(), row.version(), false);
    }

    static OrderDto fromDetail(UUID restaurantId, OrderWire.Detail detail, List<OrderWire.Cancellation> cancellations) {
        String currency = detail.currency() == null ? "" : detail.currency();
        BigDecimal original = detail.amounts() == null ? BigDecimal.ZERO : money(detail.amounts().grossMinor(), currency);
        BigDecimal cancelled = money(detail.cancelledGrossMinor(), currency);
        BigDecimal due = detail.currentDueMinor() == null ? original.subtract(cancelled)
                : money(detail.currentDueMinor(), currency);
        List<OrderItemDto> items = detail.items().stream().map(item -> item(item, currency)).toList();
        boolean partly = detail.items().stream().anyMatch(item -> item.cancelledQuantity() > 0);
        Instant asOf = detail.updatedAt() == null ? detail.placedAt() : detail.updatedAt();
        return build(restaurantId, detail.orderId(), detail.orderNo(), detail.tableId(), detail.tableSessionId(),
                detail.fulfillmentStatus(), detail.paymentStatus(), currency, original, cancelled, due, items,
                cancellations.stream().map(OrderMapping::cancellation).toList(), detail.placedAt(), asOf,
                detail.servedAt(), detail.version(), partly);
    }

    private static OrderDto build(UUID restaurantId, UUID id, String orderNo, UUID tableId, UUID sessionId,
                                  String fulfillment, String payment, String currency, BigDecimal original,
                                  BigDecimal cancelled, BigDecimal payable, List<OrderItemDto> items,
                                  List<CancellationDto> cancellations, Instant placedAt, Instant asOf, Instant servedAt, Long version,
                                  boolean partiallyCancelled) {
        boolean served = "SERVED".equals(fulfillment) || "COMPLETED".equals(fulfillment);
        return new OrderDto(id, restaurantId, orderNo, tableId, sessionId,
                business(fulfillment, partiallyCancelled), served ? DeliveryStatus.SERVED : DeliveryStatus.PENDING,
                placedAt, servedAt, new PreparationProjectionDto(preparation(fulfillment), null, null, 0, asOf),
                payment == null ? null : new PaymentProjectionDto(payment(payment), 0, asOf),
                currency, original, cancelled, payable, items, cancellations, version);
    }

    private static OrderBusinessStatus business(String fulfillment, boolean partiallyCancelled) {
        return switch (fulfillment) {
            case "CANCELLED" -> OrderBusinessStatus.CANCELLED;
            case "COMPLETED" -> OrderBusinessStatus.COMPLETED;
            case "RECEIVED", "PREPARING", "READY", "SERVED" ->
                    partiallyCancelled ? OrderBusinessStatus.PARTIALLY_CANCELLED : OrderBusinessStatus.ACCEPTED;
            default -> throw new IllegalArgumentException("unknown fulfillmentStatus");
        };
    }

    private static PreparationStatus preparation(String fulfillment) {
        return switch (fulfillment) {
            case "RECEIVED" -> PreparationStatus.WAITING;
            case "PREPARING" -> PreparationStatus.PREPARING;
            case "READY", "SERVED", "COMPLETED" -> PreparationStatus.READY;
            case "CANCELLED" -> PreparationStatus.CANCELLED;
            default -> throw new IllegalArgumentException("unknown fulfillmentStatus");
        };
    }

    private static PaymentSummaryStatus payment(String payment) {
        return switch (payment) {
            case "NOT_REQUESTED" -> PaymentSummaryStatus.NOT_PAID;
            case "PENDING" -> PaymentSummaryStatus.PENDING;
            case "PAID" -> PaymentSummaryStatus.PAID;
            default -> PaymentSummaryStatus.UNKNOWN;
        };
    }

    private static OrderItemDto item(OrderWire.Item item, String currency) {
        List<OrderItemOptionDto> options = new ArrayList<>();
        for (OrderWire.Extra extra : item.extras()) {
            options.add(new OrderItemOptionDto(extra.name(), OptionAction.ADD, Math.max(extra.quantity(), 1),
                    money(extra.unitPriceMinor(), currency)));
        }
        for (OrderWire.Removed removed : item.removedIngredients()) {
            options.add(new OrderItemOptionDto(removed.name(), OptionAction.REMOVE, 1, BigDecimal.ZERO));
        }
        return new OrderItemDto(item.orderItemId(), item.productId(), item.name(), item.quantity(),
                item.cancelledQuantity(), money(item.unitGrossMinor(), currency),
                money(item.lineGrossMinor(), currency), item.note(), options);
    }

    private static CancellationDto cancellation(OrderWire.Cancellation cancellation) {
        OrderWire.Reason reason = cancellation.reasons().isEmpty() ? null : cancellation.reasons().getFirst();
        return new CancellationDto(cancellation.cancellationId(), source(cancellation.requestedBy()),
                status(cancellation.status()), reason == null ? null : reason.code(),
                reason == null ? null : reason.text(), cancellation.requestedAt(), cancellation.decidedAt(),
                cancellation.items().stream().map(i -> new CancellationItemDto(i.orderItemId(), i.quantity())).toList());
    }

    private static CancellationSource source(OrderWire.Actor actor) {
        String role = actor == null ? null : actor.role();
        if ("KITCHEN".equals(role)) return CancellationSource.KITCHEN;
        return "WAITER".equals(role) ? CancellationSource.WAITER : CancellationSource.CUSTOMER;
    }

    private static CancellationStatus status(String status) {
        return switch (status) {
            case "REQUESTED" -> CancellationStatus.REQUESTED;
            case "APPLIED" -> CancellationStatus.APPROVED;
            case "REJECTED" -> CancellationStatus.REJECTED;
            default -> throw new IllegalArgumentException("unknown cancellation status");
        };
    }

    /** Minor units to a decimal amount using the currency's own number of decimals (two when unknown). */
    static BigDecimal money(Long minor, String currency) {
        if (minor == null) return BigDecimal.ZERO;
        int digits = 2;
        try {
            digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        } catch (IllegalArgumentException _) {
            // unknown or empty currency code: keep two decimals
        }
        return BigDecimal.valueOf(minor, digits);
    }
}
