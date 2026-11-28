package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.ServeOrderCommandRequest;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.transition.FulfillmentTransitions;
import com.restofy.order.infrastructure.persistence.OrderServeCommandStore;
import com.restofy.order.infrastructure.persistence.OrderServeCommandStore.Decision;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class OrderServeCommandService {

    public record Result(Decision decision, boolean duplicate) {
    }

    private final OrderRepository orders;
    private final OrderServeCommandStore commands;
    private final OrderFulfillmentService fulfillment;
    private final Clock clock;

    public OrderServeCommandService(OrderRepository orders, OrderServeCommandStore commands,
                                   OrderFulfillmentService fulfillment, Clock clock) {
        this.orders = orders;
        this.commands = commands;
        this.fulfillment = fulfillment;
        this.clock = clock;
    }

    @Transactional
    public Result serve(StaffActor staff, UUID orderId, String key, ServeOrderCommandRequest request) {
        requireStaffScope(staff, request.restaurantId());
        if (!staff.userId().equals(request.requestedByUserId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "The delegated staff identity does not match");
        }
        if (!request.commandId().toString().equals(key)) {
            throw new BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY, "Idempotency-Key must match commandId");
        }

        Order order = orders.findForUpdate(orderId, staff.restaurantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        String digest = IdempotencyKeys.sha256(String.join("|", orderId.toString(),
                request.tableSessionId().toString(), request.expectedVersion().toString(),
                staff.userId().toString(), request.requestedAt().toString()));
        var recorded = commands.find(staff.restaurantId(), request.commandId());
        if (recorded.isPresent()) {
            Decision first = recorded.get();
            if (!first.requestDigest().equals(digest)) {
                throw conflict();
            }
            return new Result(first, true);
        }

        ErrorCode rejection = rejection(order, request);
        Instant servedAt = null;
        Instant decidedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (rejection == null) {
            fulfillment.applyWaiterServe(order, staff, request.commandId());
            servedAt = order.getUpdatedAt();
            decidedAt = servedAt;
        }
        Decision decision = new Decision(request.commandId(), orderId, order.getTableSessionId(), digest,
                order.getVersion(), decidedAt, servedAt, rejection);
        // The same key may have raced on a different order. Roll back all effects if that command won the key.
        if (!commands.insert(staff.restaurantId(), staff.userId(), request.requestedAt(), decision)) {
            throw conflict();
        }
        return new Result(decision, false);
    }

    public Decision status(StaffActor staff, UUID restaurantId, UUID orderId, UUID commandId) {
        requireStaffScope(staff, restaurantId);
        return commands.find(staff.restaurantId(), commandId)
                .filter(decision -> orderId.equals(decision.orderId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_SERVE_COMMAND_NOT_FOUND,
                        "Serve command not found"));
    }

    private static void requireStaffScope(StaffActor staff, UUID restaurantId) {
        if (!staff.restaurantId().equals(restaurantId)
                || !FulfillmentTransitions.mayStaffSet(staff.role(), FulfillmentStatus.SERVED)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Staff is not authorised to serve for this restaurant");
        }
    }

    private static ErrorCode rejection(Order order, ServeOrderCommandRequest request) {
        if (!order.getTableSessionId().equals(request.tableSessionId())) {
            return ErrorCode.ORDER_SESSION_MISMATCH;
        }
        if (!order.getVersion().equals(request.expectedVersion())) {
            return ErrorCode.ORDER_VERSION_CONFLICT;
        }
        return switch (order.getFulfillmentStatus()) {
            case READY -> null;
            case CANCELLED -> ErrorCode.ORDER_CANCELLED;
            case SERVED, COMPLETED -> ErrorCode.ORDER_ALREADY_SERVED;
            case RECEIVED, PREPARING -> ErrorCode.ORDER_NOT_READY;
        };
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.ORDER_COMMAND_CONFLICT, "The command identifier is already in use");
    }
}
