package com.restofy.order.api.controller;

import com.restofy.order.api.dto.request.AddCartItemRequest;
import com.restofy.order.api.dto.request.UpdateCartItemQuantityRequest;
import com.restofy.order.api.dto.response.CartResponse;
import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The QR guest's own cart. "current" is resolved from the authenticated guest session; there is no way to address
 * another guest's, table's or restaurant's cart through this API.
 */
@RestController
@RequestMapping("/api/v1/carts/current")
@Tag(name = "Cart", description = "Guest cart bound to the active QR table session")
@SecurityRequirement(name = "guestSession")
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    @GetMapping
    @Operation(summary = "Get the current cart (empty when nothing was added yet; never creates a cart)")
    public CartResponse get(@AuthenticationPrincipal GuestSessionContext guest) {
        return carts.getCurrent(guest);
    }

    @PostMapping("/items")
    @Operation(summary = "Add a product (optionally customized); identical selections merge into one line")
    public CartResponse addItem(@AuthenticationPrincipal GuestSessionContext guest,
                                @Valid @RequestBody AddCartItemRequest request) {
        return carts.addItem(guest, request);
    }

    @PatchMapping("/items/{cartItemId}")
    @Operation(summary = "Set the quantity of a line (1..max); use DELETE to remove it")
    public CartResponse updateQuantity(@AuthenticationPrincipal GuestSessionContext guest,
                                       @PathVariable UUID cartItemId,
                                       @Valid @RequestBody UpdateCartItemQuantityRequest request) {
        return carts.updateQuantity(guest, cartItemId, request);
    }

    @DeleteMapping("/items/{cartItemId}")
    @Operation(summary = "Remove one line from the cart")
    public CartResponse removeItem(@AuthenticationPrincipal GuestSessionContext guest,
                                   @PathVariable UUID cartItemId,
                                   @RequestParam(required = false) Long expectedCartVersion) {
        return carts.removeItem(guest, cartItemId, expectedCartVersion);
    }

    @DeleteMapping("/items")
    @Operation(summary = "Remove all lines (idempotent)")
    public CartResponse clear(@AuthenticationPrincipal GuestSessionContext guest,
                              @RequestParam(required = false) Long expectedCartVersion) {
        return carts.clear(guest, expectedCartVersion);
    }
}
