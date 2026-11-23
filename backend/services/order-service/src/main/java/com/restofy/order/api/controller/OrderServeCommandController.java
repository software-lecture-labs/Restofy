package com.restofy.order.api.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.order.api.dto.request.ServeOrderCommandRequest;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.application.service.OrderServeCommandService;
import com.restofy.order.domain.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/orders/{orderId}")
public class OrderServeCommandController {

    public record Applied(UUID commandId, UUID orderId, UUID tableSessionId, long orderVersion, Instant servedAt,
                          String outcome) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Recorded(UUID commandId, UUID orderId, UUID tableSessionId, long orderVersion, Instant decidedAt,
                           Instant servedAt, String status, String rejectionCode) {
    }

    private final OrderServeCommandService commands;

    public OrderServeCommandController(OrderServeCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/serve")
    @Operation(summary = "Mark a ready order as served through a signed Waiter command")
    public Applied serve(@AuthenticationPrincipal StaffActor staff, @PathVariable UUID orderId,
                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                         @Valid @RequestBody ServeOrderCommandRequest request) {
        var result = commands.serve(staff, orderId, key, request);
        var decision = result.decision();
        // Write the HTTP error only after the service transaction has committed the rejection.
        if (decision.rejection() != null) {
            throw new BusinessException(decision.rejection(), "The order cannot be served by this command");
        }
        return new Applied(decision.commandId(), orderId, decision.tableSessionId(), decision.orderVersion(),
                decision.servedAt(), result.duplicate() ? "DUPLICATE" : "APPLIED");
    }

    @GetMapping("/serve-commands/{commandId}")
    @Operation(summary = "Read the original decision of a signed Waiter serve command")
    public Recorded status(@AuthenticationPrincipal StaffActor staff, @PathVariable UUID orderId,
                           @PathVariable UUID commandId, @RequestParam UUID restaurantId) {
        var decision = commands.status(staff, restaurantId, orderId, commandId);
        String rejection = decision.rejection() == null ? null : decision.rejection().name();
        return new Recorded(commandId, orderId, decision.tableSessionId(), decision.orderVersion(),
                decision.decidedAt(), decision.servedAt(), rejection == null ? "APPLIED" : "REJECTED", rejection);
    }
}
