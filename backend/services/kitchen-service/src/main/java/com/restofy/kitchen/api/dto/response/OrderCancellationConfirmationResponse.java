package com.restofy.kitchen.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage;
import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation;
import com.restofy.kitchen.domain.enums.ConfirmationStatus;
import com.restofy.kitchen.domain.enums.OrderOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An Order service request for the kitchen's confirmation of a cancellation: the kitchen's answer and, separately, the
 * Order service's final word. It describes the REQUEST, never the order: {@code status=APPROVED} means the kitchen
 * agreed, not that the order is cancelled. {@code orderOutcome} says what the Order service did: {@code APPLIED},
 * {@code REJECTED} (possible also after the kitchen agreed) or {@code SUPERSEDED}; {@code null} while undecided.
 * {@code orderDecidedAt} and {@code orderRejectionCode} come only with an Order event and may stay {@code null}.
 * The answer fields stay {@code null} until a staff member answers; a request with an {@code orderOutcome} can no
 * longer be answered.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrderCancellationConfirmationResponse(
		UUID cancellationId,
		UUID orderId,
		String scope,
		List<OrderCancellationRequestedMessage.Item> items,
		Instant requestedAt,
		ConfirmationStatus status,
		Instant answeredAt,
		UUID answeredByUserId,
		String rejectionCode,
		OrderOutcome orderOutcome,
		Instant orderDecidedAt,
		String orderRejectionCode) {

	public static OrderCancellationConfirmationResponse from(OrderCancellationConfirmation confirmation,
			List<OrderCancellationRequestedMessage.Item> items) {
		return new OrderCancellationConfirmationResponse(confirmation.getCancellationId(), confirmation.getOrderId(),
				confirmation.getScope(), items, confirmation.getRequestedAt(), confirmation.getStatus(), confirmation.getAnsweredAt(),
				confirmation.getAnsweredByUserId(), confirmation.getRejectionCode(), confirmation.getOrderOutcome(),
				confirmation.getOrderDecidedAt(), confirmation.getOrderRejectionCode());
	}
}
