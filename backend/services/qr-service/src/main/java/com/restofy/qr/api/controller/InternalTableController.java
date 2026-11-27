package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.response.InternalTablePage;
import com.restofy.qr.api.dto.response.InternalTableResponse;
import com.restofy.qr.application.service.InternalTableQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only table list and detail for the Waiter service. The tenant is the verified service credential's restaurant;
 * the {@code restaurantId} query parameter Waiter sends is only a cross-check and never selects the data.
 */
@RestController
@Profile("qr-order-inbound")
@RequestMapping(InternalTableController.BASE)
public class InternalTableController {
    public static final String BASE = "/api/v1/internal/tables";
    private static final String RESTAURANT_ID = "restaurantId";

    private final InternalTableQueryService tables;

    public InternalTableController(InternalTableQueryService tables) {
        this.tables = tables;
    }

    @GetMapping
    public ResponseEntity<InternalTablePage> list(@AuthenticationPrincipal UUID restaurantId,
            @RequestParam(name = RESTAURANT_ID, required = false) UUID requestedRestaurantId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "200") int size) {
        requireSameTenant(restaurantId, requestedRestaurantId);
        return noStore(tables.list(restaurantId, page, size));
    }

    @GetMapping("/{tableId}")
    public ResponseEntity<InternalTableResponse> get(@AuthenticationPrincipal UUID restaurantId,
            @RequestParam(name = RESTAURANT_ID, required = false) UUID requestedRestaurantId, @PathVariable UUID tableId) {
        requireSameTenant(restaurantId, requestedRestaurantId);
        return noStore(tables.get(restaurantId, tableId));
    }

    private static void requireSameTenant(UUID verified, UUID requested) {
        if (requested != null && !requested.equals(verified)) {
            throw new IllegalArgumentException("Restaurant does not match the verified service credential.");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
