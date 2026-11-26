package com.restofy.qr.api.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.qr.api.dto.request.OrderCartActivity;
import com.restofy.qr.application.service.GuestSessionValidationService;
import com.restofy.qr.application.service.OrderGuestSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@Profile("qr-order-inbound")
@RequestMapping(OrderGuestSessionController.BASE)
public class OrderGuestSessionController {
    public static final String BASE = "/api/v1/internal/qr";
    public static final String VALIDATE = "/guest-sessions/validate";
    public static final String STATE = "/table-sessions/{sessionId}/guests/{guestId}/state";
    public static final String CART_ACTIVITY = "/events/cart-activity";
    /** Authority of a verified Order caller; Waiter reaches the same validation route with its own authority. */
    public static final String ORDER_CALLER = "ORDER_QR_GUEST_SESSION";

    private final GuestSessionValidationService validation;
    private final OrderGuestSessionService sessions;

    public OrderGuestSessionController(GuestSessionValidationService validation, OrderGuestSessionService sessions) {
        this.validation = validation;
        this.sessions = sessions;
    }

    @PostMapping(path = VALIDATE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GuestContext> validateGuest(@AuthenticationPrincipal UUID restaurantId, Authentication caller,
                                                  @Valid @RequestBody GuestToken request) {
        var result = validation.validateForOrder(request.guestToken(), restaurantId);
        // Only Order snapshots the table number into its orders; Waiter's answer stays exactly as it was.
        String tableNumber = isOrder(caller) ? result.tableNumber() : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new GuestContext(result.restaurantId(),
                result.tableId(), tableNumber, result.tableSessionId(), result.guestSessionId(), "ACTIVE", result.checkedAt()));
    }

    @GetMapping(STATE)
    public ResponseEntity<OrderGuestSessionService.StateResponse> checkoutState(@AuthenticationPrincipal UUID restaurantId,
            @PathVariable UUID sessionId, @PathVariable UUID guestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.state(restaurantId, sessionId, guestId));
    }

    @PostMapping(path = CART_ACTIVITY, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> activity(@AuthenticationPrincipal UUID restaurantId, @Valid @RequestBody OrderCartActivity event) {
        sessions.recordCartActivity(restaurantId, event);
        return ResponseEntity.noContent().build();
    }

    public record GuestToken(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String guestToken) {
        @Override public String toString() { return "Order guest validation request"; }
    }

    private static boolean isOrder(Authentication caller) {
        return caller.getAuthorities().stream().anyMatch(authority -> ORDER_CALLER.equals(authority.getAuthority()));
    }

    public record GuestContext(UUID restaurantId, UUID tableId, @JsonInclude(JsonInclude.Include.NON_NULL) String tableNumber,
                               UUID tableSessionId, UUID guestSessionId, String state, Instant checkedAt) { }
}
