package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.KitchenOrderEventRequest;
import com.restofy.order.api.dto.response.OrderEventResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.service.OrderFulfillmentService;
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

    public InternalOrderEventController(OrderFulfillmentService fulfillment) {
        this.fulfillment = fulfillment;
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
}
