package com.restofy.qr.api.controller;

import com.restofy.qr.application.service.GuestSessionValidationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Menu's own guest check (QR-REQUEST-01). Menu sends only the guest token from the {@code restofy_guest} cookie and
 * learns which restaurant's menu to show. The check is the same as Order's (local timers first, Order asked only when
 * they cannot prove ACTIVE) and is read-only: it never refreshes session activity. The response is deliberately narrow.
 */
@RestController
@Profile("qr-order-inbound")
@RequestMapping(MenuGuestSessionController.BASE)
public class MenuGuestSessionController {
    public static final String BASE = "/api/v1/internal/qr/menu";
    public static final String VALIDATE = "/guest-sessions/validate";

    private final GuestSessionValidationService validation;

    public MenuGuestSessionController(GuestSessionValidationService validation) {
        this.validation = validation;
    }

    /** {@code restaurantId} is QR's own configured restaurant (set by the Menu filter); Menu does not send one. */
    @PostMapping(path = VALIDATE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MenuGuestContext> validateGuest(@AuthenticationPrincipal UUID restaurantId,
                                                          @Valid @RequestBody GuestToken request) {
        var result = validation.validateForOrder(request.guestToken(), restaurantId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MenuGuestContext(result.restaurantId(), "ACTIVE"));
    }

    public record GuestToken(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String guestToken) {
        @Override public String toString() { return "Menu guest validation request"; }
    }

    public record MenuGuestContext(UUID restaurantId, String state) { }
}
