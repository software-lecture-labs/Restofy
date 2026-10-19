package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.api.dto.request.ChangeProductAvailabilityRequest;
import com.restofy.kitchen.api.dto.response.ProductAvailabilityResponse;
import com.restofy.kitchen.api.support.IdempotencyKey;
import com.restofy.kitchen.application.service.ProductAvailabilityService;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kitchen/products")
public class KitchenProductController {

	private final ProductAvailabilityService availability;

	public KitchenProductController(ProductAvailabilityService availability) {
		this.availability = availability;
	}

	/**
	 * Asks the Menu service to change a product's sales availability. KITCHEN role only (ADMIN reads, never writes);
	 * the restaurant and the user come from the verified principal, never from the body or headers. The required
	 * {@code Idempotency-Key} is the command id. 200 only with the state Menu confirmed; if Menu cannot be reached or
	 * is not integrated the outcome is unknown and the answer is 503 - never a success.
	 */
	@PatchMapping("/{productId}/availability")
	public ProductAvailabilityResponse change(@AuthenticationPrincipal KitchenPrincipal principal,
			@PathVariable UUID productId, @RequestHeader(IdempotencyKey.HEADER) List<String> commandIdHeader,
			@Valid @RequestBody ChangeProductAvailabilityRequest request) {
		return ProductAvailabilityResponse.from(availability.change(principal.restaurantId(), principal.userId(),
				IdempotencyKey.parse(commandIdHeader), productId, request));
	}
}
