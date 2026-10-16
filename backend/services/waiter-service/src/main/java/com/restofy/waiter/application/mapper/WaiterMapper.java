package com.restofy.waiter.application.mapper;

import com.restofy.waiter.api.dto.response.OrderResponse;
import com.restofy.waiter.api.dto.response.ServiceRequestResponse;
import com.restofy.waiter.api.dto.response.TableSessionResponse;
import com.restofy.waiter.api.dto.response.TableSummaryResponse;
import com.restofy.waiter.domain.entity.ServiceRequest;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import com.restofy.waiter.infrastructure.client.order.CancellationDto;
import com.restofy.waiter.infrastructure.client.order.OrderDto;
import com.restofy.waiter.infrastructure.client.order.OrderItemDto;
import com.restofy.waiter.infrastructure.client.order.OrderItemOptionDto;
import com.restofy.waiter.infrastructure.client.qr.QrSessionDto;
import com.restofy.waiter.infrastructure.client.qr.QrTableDto;

import java.util.List;

/**
 * Maps downstream DTOs and local rows to the public API. Guest identifiers and staff ids of downstream
 * records are dropped on purpose: the waiter view does not need them.
 */
public final class WaiterMapper {

    private WaiterMapper() {
    }

    public static int openCallCount(List<ServiceRequest> unresolved) {
        return (int) unresolved.stream().filter(request -> request.getRequestType() != ServiceRequestType.BILL).count();
    }

    public static boolean billRequested(List<ServiceRequest> unresolved) {
        return unresolved.stream().anyMatch(request -> request.getRequestType() == ServiceRequestType.BILL);
    }

    public static TableSummaryResponse toSummary(QrTableDto table, int activeOrderCount, List<ServiceRequest> unresolved) {
        return new TableSummaryResponse(table.id(), table.tableNumber(), table.displayName(), table.capacity(),
                table.active(), table.operationalStatus(), toSession(table.currentSession()), activeOrderCount,
                openCallCount(unresolved), billRequested(unresolved));
    }

    public static ServiceRequestResponse toServiceRequest(ServiceRequest request) {
        return new ServiceRequestResponse(request.getId(), request.getRequestType(), request.getStatus(),
                request.getMessage(), request.getCreatedAt(), request.getClaimedAt());
    }

    public static OrderResponse toOrder(OrderDto order, QrTableDto table) {
        QrSessionDto live = table.currentSession();
        boolean current = live != null && live.id().equals(order.tableSessionId());
        return new OrderResponse(order.id(), order.orderNumber(),
                new OrderResponse.TableRef(table.id(), table.tableNumber(), table.displayName(), table.operationalStatus()),
                new OrderResponse.SessionRef(order.tableSessionId(), current),
                order.businessStatus(), order.deliveryStatus(),
                order.preparation() == null ? null : new OrderResponse.Preparation(order.preparation().status(),
                        order.preparation().startedAt(), order.preparation().readyAt(), order.preparation().asOf()),
                order.payment() == null ? null : new OrderResponse.Payment(order.payment().status(), order.payment().asOf()),
                order.acceptedAt(), order.servedAt(), order.currency(), order.originalGrossTotal(),
                order.cancelledGrossTotal(), order.payableTotal(),
                order.items().stream().map(WaiterMapper::toItem).toList(),
                order.cancellations().stream().map(WaiterMapper::toCancellation).toList(),
                order.version());
    }

    private static TableSessionResponse toSession(QrSessionDto session) {
        return session == null ? null : new TableSessionResponse(session.id(), session.status(), session.openedAt(),
                session.lastActivityAt(), session.scanCount());
    }

    private static OrderResponse.Item toItem(OrderItemDto item) {
        return new OrderResponse.Item(item.id(), item.productId(), item.productName(), item.quantity(),
                item.cancelledQuantity(), item.unitGrossPrice(), item.lineGrossTotal(), item.note(),
                item.options().stream().map(WaiterMapper::toOption).toList());
    }

    private static OrderResponse.Option toOption(OrderItemOptionDto option) {
        return new OrderResponse.Option(option.name(), option.action(), option.quantity(), option.unitPriceDelta());
    }

    private static OrderResponse.Cancellation toCancellation(CancellationDto cancellation) {
        return new OrderResponse.Cancellation(cancellation.id(), cancellation.source(), cancellation.status(),
                cancellation.reasonCode(), cancellation.reasonText(), cancellation.requestedAt(),
                cancellation.decidedAt(), cancellation.items().stream()
                .map(item -> new OrderResponse.CancelledItem(item.orderItemId(), item.quantity())).toList());
    }
}
