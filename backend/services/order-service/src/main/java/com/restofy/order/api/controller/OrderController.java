package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.CheckoutRequest;
import com.restofy.order.api.dto.request.FulfillmentStatusRequest;
import com.restofy.order.api.dto.response.FulfillmentStatusResponse;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.application.service.OrderFulfillmentService;
import com.restofy.order.api.dto.response.OrderDetailResponse;
import com.restofy.order.api.dto.response.OrderHistoryResponse;
import com.restofy.order.api.dto.response.OrderListResponse;
import com.restofy.order.api.dto.response.OrderResponse;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.service.OrderCheckoutService;
import com.restofy.order.application.service.OrderListQuery;
import com.restofy.order.application.service.OrderQueryService;
import com.restofy.order.application.service.RequestActor;
import com.restofy.order.application.service.OrderCheckoutService.CheckoutResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Order placement for a QR guest. Reading and managing orders arrives in a later phase. */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Order", description = "Order placement, detail, status history and status changes")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final OrderCheckoutService checkout;
    private final OrderQueryService queries;
    private final OrderFulfillmentService fulfillment;

    public OrderController(OrderCheckoutService checkout, OrderQueryService queries,
                           OrderFulfillmentService fulfillment) {
        this.checkout = checkout;
        this.queries = queries;
        this.fulfillment = fulfillment;
    }

    @GetMapping
    @Operation(summary = "List orders of a table session or table (active, completed or cancelled), paged",
            description = "view filters on the FULFILLMENT status only: active = RECEIVED, PREPARING, READY, SERVED; "
                    + "completed = COMPLETED; cancelled = CANCELLED (default active). Payment is a separate axis: use "
                    + "paymentStatus to filter it. A guest always gets only their own orders of their own table "
                    + "session (a foreign tableSessionId/tableId is 403); staff (ADMIN, WAITER) get their restaurant orders, "
                    + "optionally narrowed by tableSessionId (one visit) or tableId (the table across visits). "
                    + "page is 0-based, limit 1-100 (default 20), sort placedAt,desc (default) or placedAt,asc.")
    @SecurityRequirement(name = "guestSession")
    @SecurityRequirement(name = "staff")
    public OrderListResponse list(Authentication authentication,
                                  @RequestParam(required = false) UUID tableSessionId,
                                  @RequestParam(required = false) UUID tableId,
                                  @RequestParam(required = false) String view,
                                  @RequestParam(required = false) String paymentStatus,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String sort) {
        return queries.list(RequestActor.from(authentication),
                OrderListQuery.of(tableSessionId, tableId, view, paymentStatus, page, limit, sort));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Order detail with its immutable price snapshot and both independent statuses",
            description = "A guest sees only their own orders while their table session is active. Staff (ADMIN, "
                    + "WAITER) see the orders of their own restaurant. An order that does not exist, belongs to "
                    + "another restaurant or to another guest is a 404; KITCHEN is 403.")
    @SecurityRequirement(name = "guestSession")
    @SecurityRequirement(name = "staff")
    public OrderDetailResponse detail(Authentication authentication, @PathVariable UUID orderId) {
        return queries.detail(RequestActor.from(authentication), orderId);
    }

    @GetMapping("/{orderId}/history")
    @Operation(summary = "Append-only status timeline of an order (fulfillment and payment axes)",
            description = "Oldest first. Staff identifiers are visible to ADMIN only.")
    @SecurityRequirement(name = "guestSession")
    @SecurityRequirement(name = "staff")
    public OrderHistoryResponse history(Authentication authentication, @PathVariable UUID orderId) {
        return queries.history(RequestActor.from(authentication), orderId);
    }

    @PatchMapping("/{orderId}/fulfillment-status")
    @Operation(summary = "Move an order one step along its fulfillment lifecycle (staff only)",
            description = "RECEIVED, PREPARING, READY, SERVED, COMPLETED, one step at a time. KITCHEN sets PREPARING "
                    + "and READY, WAITER sets SERVED and COMPLETED, ADMIN sets COMPLETED; any other role is 403. "
                    + "Skipping a step, going back or leaving COMPLETED/CANCELLED is 409 INVALID_STATUS_TRANSITION. "
                    + "CANCELLED cannot be set here (cancellation is a separate process). COMPLETED does not require "
                    + "payment: payment is an independent status. Repeating the step you just made is a harmless "
                    + "no-op (changed=false). An order of another restaurant is 404. Guests cannot call this.")
    @SecurityRequirement(name = "staff")
    @ApiResponse(responseCode = "200", description = "Status changed (changed=true) or already set by you (false)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid staff credential")
    @ApiResponse(responseCode = "403", description = "Not staff, or this role may not set that status")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND (also for another restaurant's order)")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR (unknown status, bad reason)")
    public FulfillmentStatusResponse changeFulfillmentStatus(@AuthenticationPrincipal StaffActor staff,
                                                             @PathVariable UUID orderId,
                                                             @Valid @RequestBody FulfillmentStatusRequest request) {
        return fulfillment.changeByStaff(staff, orderId, request.status(), request.reason());
    }

    @PostMapping
    @Operation(summary = "Turn the current cart into an order (idempotent per Idempotency-Key)",
            description = "The server re-prices the cart from the catalog and orders only if the client confirmed "
                    + "the same cart version and total. Repeating a finished request with the same key and body "
                    + "returns the same order (header Idempotent-Replayed: true). The order number (ORD-000001) is a "
                    + "display identifier only and never grants access.")
    @SecurityRequirement(name = "guestSession")
    @ApiResponse(responseCode = "201", description = "Order placed (or replayed for the same Idempotency-Key)")
    @ApiResponse(responseCode = "400", description = "Missing/invalid Idempotency-Key, malformed body or "
                    + "unknown fields (prices and ids are never accepted from the client)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid guest session")
    @ApiResponse(responseCode = "409", description = "PRICE_CHANGED, CART_VERSION_CONFLICT, "
                    + "IDEMPOTENCY_CONFLICT, CART_ALREADY_CHECKED_OUT, PRODUCT_UNAVAILABLE, TAX_DATA_MISSING or "
                    + "TABLE_SESSION_NOT_ACTIVE")
    @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR or CART_EMPTY")
    @ApiResponse(responseCode = "503", description = "QR or Menu service unavailable (fail closed)")
    public ResponseEntity<OrderResponse> placeOrder(
            @AuthenticationPrincipal GuestSessionContext guest,
            @Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER, required = true,
                    description = "8-120 characters of letters, digits, '.', '_', ':' or '-'. Scoped to the guest.")
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        CheckoutResult result = checkout.checkout(guest, idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response.body(result.order());
    }
}
