package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.DeliveryStatus;
import com.restofy.waiter.domain.enums.OrderBusinessStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredList;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/**
 * One order as Waiter sees it, built by {@link OrderMapping} from Order's real contract. A row of a list has no
 * items, cancellations or {@code version} (only the detail has them), and Order does not report {@code servedAt}
 * on reads, so those may be empty/null; a command must therefore read the version from the detail.
 * Formerly the DRAFT v0 model: one order with its snapshot lines. Order owns the business and delivery axes;
 * {@code preparation} and {@code payment} are projections and may be absent (for example when the Kitchen
 * record has not reached Order yet), which is not the same as "not ready". Waiter assesses no closure from these
 * figures: whether a table session may close is Order's assessment for QR (ADR-QR-001 K7).
 */
public record OrderDto(UUID id, UUID restaurantId, String orderNumber, UUID tableId, UUID tableSessionId,
                       OrderBusinessStatus businessStatus, DeliveryStatus deliveryStatus,
                       Instant acceptedAt, Instant servedAt,
                       PreparationProjectionDto preparation, PaymentProjectionDto payment,
                       String currency, BigDecimal originalGrossTotal, BigDecimal cancelledGrossTotal,
                       BigDecimal payableTotal, List<OrderItemDto> items, List<CancellationDto> cancellations,
                       Long version) {

    public OrderDto {
        required(id, "order.id");
        required(restaurantId, "order.restaurantId");
        requiredText(orderNumber, "order.orderNumber");
        required(tableId, "order.tableId");
        required(tableSessionId, "order.tableSessionId");
        required(businessStatus, "order.businessStatus");
        required(deliveryStatus, "order.deliveryStatus");
        required(acceptedAt, "order.acceptedAt");
        requiredText(currency, "order.currency");
        required(originalGrossTotal, "order.originalGrossTotal");
        required(cancelledGrossTotal, "order.cancelledGrossTotal");
        required(payableTotal, "order.payableTotal");
        items = requiredList(items, "order.items");
        cancellations = requiredList(cancellations, "order.cancellations");
        if (version != null) nonNegative(version.longValue(), "order.version");
        if (businessStatus == OrderBusinessStatus.CANCELLED && deliveryStatus == DeliveryStatus.SERVED) {
            throw new IllegalArgumentException("a cancelled order cannot be served");
        }
    }

    /** This order as the serve receipt of Order describes it: delivered, at that time and at that version. */
    public OrderDto servedAs(Long newVersion, Instant newServedAt) {
        return new OrderDto(id, restaurantId, orderNumber, tableId, tableSessionId, businessStatus,
                DeliveryStatus.SERVED, acceptedAt, newServedAt, preparation, payment, currency, originalGrossTotal,
                cancelledGrossTotal, payableTotal, items, cancellations, newVersion);
    }
}
