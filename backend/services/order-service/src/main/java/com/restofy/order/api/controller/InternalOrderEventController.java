package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.KitchenOrderEventRequest;
import com.restofy.order.api.dto.request.PaymentOrderEventRequest;
import com.restofy.order.api.dto.response.OrderEventResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.service.OrderFulfillmentService;
import com.restofy.order.application.service.OrderPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. They are not routed by the API Gateway and only a verified calling service may use them
 * ({@code Authorization: Service ...}); a guest token or a staff credential is refused.
 */
@RestController
@RequestMapping("/api/v1/internal/order-events")
@Tag(name = "Internal", description = "Events from other services (service credential only, not exposed by the gateway)")
@SecurityRequirement(name = "service")
public class InternalOrderEventController {

    private final OrderFulfillmentService fulfillment;
    private final OrderPaymentService payments;

    public InternalOrderEventController(OrderFulfillmentService fulfillment, OrderPaymentService payments) {
        this.fulfillment = fulfillment;
        this.payments = payments;
    }

    @PostMapping("/kitchen")
    @Operation(summary = "Kitchen reports the preparation status of an order (WAITING, PREPARING, READY)",
            description = "Kitchen V1 maps to the order lifecycle: WAITING = RECEIVED, PREPARING, READY. The event id "
                    + "makes redelivery safe: a repeated id returns the original outcome with duplicate=true. A late or "
                    + "repeated status is acknowledged as IGNORED_STALE, an event for a cancelled order as "
                    + "IGNORED_ORDER_CLOSED. A status that would skip a step is 409 INVALID_STATUS_TRANSITION and is "
                    + "not remembered, so the sender can retry it after the missing step. An order of another "
                    + "restaurant than the one named is 404.")
    @ApiResponse(responseCode = "200", description = "Acknowledged (APPLIED, IGNORED_STALE or IGNORED_ORDER_CLOSED)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the Kitchen service")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION or IDEMPOTENCY_CONFLICT")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public OrderEventResponse kitchen(@AuthenticationPrincipal ServiceActor service,
                                      @Valid @RequestBody KitchenOrderEventRequest event) {
        return fulfillment.applyKitchenEvent(service, event);
    }

    @PostMapping("/payment")
    @Operation(summary = "Payment reports the payment status of an order (PENDING, PAID, FAILED, CANCELLED)",
            description = "The only way an order's payment status changes; it never affects the fulfillment status. "
                    + "PENDING and PAID must carry the order's current due amount and currency (409 PAYMENT_MISMATCH "
                    + "otherwise). The event id makes redelivery safe (duplicate=true returns the original outcome); "
                    + "an event older than the last applied one, a repeated status or a PENDING after the payment "
                    + "ended is IGNORED_STALE; events for a cancelled order are IGNORED_ORDER_CLOSED, except PAID, which "
                    + "is 409 because the money needs a manual refund decision. A move that is not legal (for example "
                    + "PAID before PENDING) is 409 INVALID_STATUS_TRANSITION and is not remembered, so the sender can "
                    + "retry after the missing step. An order of another restaurant than the one named is 404.")
    @ApiResponse(responseCode = "200", description = "Acknowledged (APPLIED, IGNORED_STALE or IGNORED_ORDER_CLOSED)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the Payment service")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION, PAYMENT_MISMATCH or IDEMPOTENCY_CONFLICT")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public OrderEventResponse payment(@AuthenticationPrincipal ServiceActor service,
                                      @Valid @RequestBody PaymentOrderEventRequest event) {
        return payments.applyPaymentEvent(service, event);
    }
}
