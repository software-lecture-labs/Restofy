package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.ConfirmClosureRequest;
import com.restofy.order.api.dto.request.PrepareClosureRequest;
import com.restofy.order.api.dto.response.ClosureResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.service.TableSessionClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service endpoints for closing a table session (ADR-QR-001). They are not routed by the API Gateway and only
 * the verified QR Service may use them ({@code Authorization: Service ...}); a guest token, a staff credential and the
 * Kitchen and Payment services are refused. This contract is a DRAFT proposed by the Order Service: the QR Service has
 * not adopted it yet, and no real QR adapter calls it.
 */
@RestController
@RequestMapping("/api/v1/internal/table-sessions")
@Tag(name = "Internal", description = "Table session closure (QR service credential only, not exposed by the gateway)")
@SecurityRequirement(name = "service")
public class InternalTableSessionController {

    private final TableSessionClosureService closures;

    public InternalTableSessionController(TableSessionClosureService closures) {
        this.closures = closures;
    }

    @PostMapping("/{tableSessionId}/closure-preparations")
    @Operation(summary = "Ask whether a table session may be closed and, if so, hold new orders for it",
            description = "The Order Service looks at ALL orders of the table session, whichever guest placed them: "
                    + "every order must be finished (COMPLETED, or CANCELLED with no refund owed) and paid; a guest who "
                    + "ordered nothing is not part of the scope; a session without orders is never eligible. state is "
                    + "PREPARED only when the outcome is ELIGIBLE: from then on checkout answers 409 TABLE_SESSION_CLOSING "
                    + "for this session and writes nothing. For NOT_ELIGIBLE or UNKNOWN (blockers say why; UNKNOWN means "
                    + "the money is not known well enough, never 'nothing owed') nothing is held and state is "
                    + "NOT_PREPARED. operationId is the QR Service's durable id of the closure: asking again with it "
                    + "returns the stored closure and never prepares a second one. Another closure that is PREPARED or "
                    + "COMPLETED for the session is 409 CLOSURE_IN_PROGRESS; an operationId used for another table "
                    + "session is 409 IDEMPOTENCY_CONFLICT. A closure never expires by time.")
    @ApiResponse(responseCode = "200", description = "PREPARED (eligible) or NOT_PREPARED (not eligible / unknown)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the QR service")
    @ApiResponse(responseCode = "409", description = "CLOSURE_IN_PROGRESS or IDEMPOTENCY_CONFLICT")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public ClosureResponse prepare(@AuthenticationPrincipal ServiceActor service, @PathVariable UUID tableSessionId,
                                   @Valid @RequestBody PrepareClosureRequest request) {
        return closures.prepare(service, tableSessionId, request);
    }

    @GetMapping("/{tableSessionId}/closure-preparations/{operationId}")
    @Operation(summary = "What became of a closure operation",
            description = "For a QR Service that lost a response: returns the stored closure (PREPARED, COMPLETED or "
                    + "RELEASED). An unknown operation, or one that belongs to another restaurant or table session, is "
                    + "404 CLOSURE_NOT_FOUND. A NOT_PREPARED answer is not stored, so it is asked again with the "
                    + "prepare call.")
    @ApiResponse(responseCode = "200", description = "The stored closure")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the QR service")
    @ApiResponse(responseCode = "404", description = "CLOSURE_NOT_FOUND")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public ClosureResponse get(@PathVariable UUID tableSessionId, @PathVariable UUID operationId,
                               @RequestParam UUID restaurantId) {
        return closures.get(restaurantId, tableSessionId, operationId);
    }

    @PostMapping("/{tableSessionId}/closure-preparations/{operationId}/confirmation")
    @Operation(summary = "The QR Service reports whether it closed the table session (CLOSED or NOT_CLOSED)",
            description = "CLOSED completes a PREPARED closure, but only while the orders still hash to the "
                    + "scopeFingerprint it was prepared on; if a payment attempt, a cancellation or anything else "
                    + "changed in between it is 409 CLOSURE_SCOPE_CHANGED and the closure stays PREPARED (orders stay "
                    + "refused) until it is reconciled. NOT_CLOSED releases the hold so orders are accepted again. "
                    + "Repeating the same confirmation is safe; the opposite one after the closure is final is 409 "
                    + "INVALID_STATUS_TRANSITION. Nothing here pays, refunds or closes anything: it only records the "
                    + "outcome.")
    @ApiResponse(responseCode = "200", description = "COMPLETED or RELEASED (also when repeated)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service credential")
    @ApiResponse(responseCode = "403", description = "Not the QR service")
    @ApiResponse(responseCode = "404", description = "CLOSURE_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "CLOSURE_SCOPE_CHANGED or INVALID_STATUS_TRANSITION")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "503", description = "Service identity verification unavailable (fail closed)")
    public ClosureResponse confirm(@AuthenticationPrincipal ServiceActor service, @PathVariable UUID tableSessionId,
                                   @PathVariable UUID operationId, @Valid @RequestBody ConfirmClosureRequest request) {
        return closures.confirm(service, tableSessionId, operationId, request);
    }
}
