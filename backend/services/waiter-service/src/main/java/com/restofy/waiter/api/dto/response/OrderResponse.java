package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.CancellationSource;
import com.restofy.waiter.domain.enums.CancellationStatus;
import com.restofy.waiter.domain.enums.DeliveryStatus;
import com.restofy.waiter.domain.enums.OptionAction;
import com.restofy.waiter.domain.enums.OrderBusinessStatus;
import com.restofy.waiter.domain.enums.PaymentSummaryStatus;
import com.restofy.waiter.domain.enums.PreparationStatus;
import com.restofy.waiter.domain.enums.TableOperationalStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One order with its four independent status axes. {@code businessStatus} and {@code deliveryStatus} are
 * owned by Order. {@code preparation} (Kitchen) and {@code payment} (Payment) are projections kept by Order;
 * each carries {@code asOf} so the reader can see how fresh it is. {@code preparation} is null when Order
 * has not received the Kitchen record yet, which does not mean "not ready".
 */
public record OrderResponse(UUID id, String orderNumber, TableRef table, SessionRef session,
                            OrderBusinessStatus businessStatus, DeliveryStatus deliveryStatus,
                            Preparation preparation, Payment payment,
                            Instant acceptedAt, Instant servedAt,
                            String currency, BigDecimal originalGrossTotal, BigDecimal cancelledGrossTotal,
                            BigDecimal payableTotal, List<Item> items, List<Cancellation> cancellations,
                            long version) {

    public record TableRef(UUID id, String tableNumber, String displayName, TableOperationalStatus operationalStatus) {
    }

    /** {@code current} is true when the order belongs to the table's live session, false for history. */
    public record SessionRef(UUID id, boolean current) {
    }

    public record Preparation(PreparationStatus status, Instant startedAt, Instant readyAt, Instant asOf) {
    }

    public record Payment(PaymentSummaryStatus status, Instant asOf) {
    }

    public record Item(UUID id, UUID productId, String productName, int quantity, int cancelledQuantity,
                       BigDecimal unitGrossPrice, BigDecimal lineGrossTotal, String note, List<Option> options) {
    }

    public record Option(String name, OptionAction action, int quantity, BigDecimal unitPriceDelta) {
    }

    public record Cancellation(UUID id, CancellationSource source, CancellationStatus status, String reasonCode,
                               String reasonText, Instant requestedAt, Instant decidedAt,
                               List<CancelledItem> items) {
    }

    public record CancelledItem(UUID orderItemId, int quantity) {
    }
}
