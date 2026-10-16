package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.CheckoutRequest;
import com.restofy.order.api.dto.response.OrderResponse;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.service.OrderCheckoutService;
import com.restofy.order.application.service.OrderCheckoutService.CheckoutResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Order placement for a QR guest. Reading and managing orders arrives in a later phase. */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Order", description = "Order placement from the guest's cart")
@SecurityRequirement(name = "guestSession")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final OrderCheckoutService checkout;

    public OrderController(OrderCheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping
    @Operation(summary = "Turn the current cart into an order (idempotent per Idempotency-Key)",
            description = "The server re-prices the cart from the catalog and orders only if the client confirmed "
                    + "the same cart version and total. Repeating a finished request with the same key and body "
                    + "returns the same order (header Idempotent-Replayed: true). The order number (ORD-000001) is a "
                    + "display identifier only and never grants access.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order placed (or replayed for the same Idempotency-Key)"),
            @ApiResponse(responseCode = "400", description = "Missing/invalid Idempotency-Key, malformed body or "
                    + "unknown fields (prices and ids are never accepted from the client)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid guest session"),
            @ApiResponse(responseCode = "409", description = "PRICE_CHANGED, CART_VERSION_CONFLICT, "
                    + "IDEMPOTENCY_CONFLICT, CART_ALREADY_CHECKED_OUT, PRODUCT_UNAVAILABLE, TAX_DATA_MISSING or "
                    + "TABLE_SESSION_NOT_ACTIVE"),
            @ApiResponse(responseCode = "422", description = "VALIDATION_ERROR or CART_EMPTY"),
            @ApiResponse(responseCode = "503", description = "QR or Menu service unavailable (fail closed)")})
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
