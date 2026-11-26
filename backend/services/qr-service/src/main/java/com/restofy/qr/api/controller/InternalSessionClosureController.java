package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.response.SessionClosureEventPage;
import com.restofy.qr.application.service.SessionClosureEventQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Durable "session reached a final state" events for the Waiter service (scope qr:session-closure-read). The tenant is
 * the verified service credential's restaurant; the {@code restaurantId} parameter is only a cross-check.
 */
@RestController
@Profile("qr-order-inbound")
@RequestMapping(InternalSessionClosureController.BASE)
public class InternalSessionClosureController {
    public static final String BASE = "/api/v1/internal/session-closures";

    private final SessionClosureEventQueryService events;

    public InternalSessionClosureController(SessionClosureEventQueryService events) {
        this.events = events;
    }

    @GetMapping
    public ResponseEntity<SessionClosureEventPage> after(@AuthenticationPrincipal UUID restaurantId,
            @RequestParam(name = "restaurantId", required = false) UUID requestedRestaurantId,
            @RequestParam(defaultValue = "0") long cursor, @RequestParam(defaultValue = "100") int limit) {
        if (requestedRestaurantId != null && !requestedRestaurantId.equals(restaurantId)) {
            throw new IllegalArgumentException("Restaurant does not match the verified service credential.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(events.after(restaurantId, cursor, limit));
    }
}
