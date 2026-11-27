package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.api.dto.request.AnswerOrderCancellationRequest;
import com.restofy.kitchen.api.dto.response.OrderCancellationConfirmationResponse;
import com.restofy.kitchen.api.support.IdempotencyKey;
import com.restofy.kitchen.application.service.OrderCancellationConfirmationService;
import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation;
import com.restofy.kitchen.infrastructure.security.KitchenPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The Order service's cancellation requests that wait for the kitchen's answer (an order already in preparation). These
 * are not Kitchen's own requests ({@code /cancellation-requests}). Answering never cancels the order: the answer goes to
 * the Order service, which decides.
 */
@RestController
@RequestMapping("/api/v1/kitchen/orders/{orderId}/order-cancellations")
public class KitchenOrderCancellationController {

	private final OrderCancellationConfirmationService confirmations;

	public KitchenOrderCancellationController(OrderCancellationConfirmationService confirmations) {
		this.confirmations = confirmations;
	}

	/** The order's requests from the Order service, newest first. KITCHEN and ADMIN may read. */
	@GetMapping
	public List<OrderCancellationConfirmationResponse> list(@AuthenticationPrincipal KitchenPrincipal principal,
			@PathVariable UUID orderId) {
		return confirmations.list(principal.restaurantId(), orderId).stream().map(this::response).toList();
	}

	/**
	 * Answers one request: APPROVED or REJECTED. KITCHEN role only (ADMIN reads, never writes). The required
	 * {@code Idempotency-Key} makes a retry safe: the first call is 202 Accepted ("the answer is recorded, the Order
	 * service decides"), the same answer sent again is 200, the same key for anything else is 409. A request that was
	 * already answered is 409.
	 */
	@PostMapping("/{cancellationId}/answer")
	public ResponseEntity<OrderCancellationConfirmationResponse> answer(@AuthenticationPrincipal KitchenPrincipal principal,
			@PathVariable UUID orderId, @PathVariable UUID cancellationId,
			@RequestHeader(IdempotencyKey.HEADER) List<String> commandIdHeader,
			@Valid @RequestBody AnswerOrderCancellationRequest body) {
		OrderCancellationConfirmationService.Answered answered = confirmations.answer(principal.restaurantId(),
				principal.userId(), IdempotencyKey.parse(commandIdHeader), orderId, cancellationId, body.decision());
		return ResponseEntity.status(answered.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
				.body(response(answered.confirmation()));
	}

	private OrderCancellationConfirmationResponse response(OrderCancellationConfirmation confirmation) {
		return OrderCancellationConfirmationResponse.from(confirmation, confirmations.items(confirmation));
	}
}
