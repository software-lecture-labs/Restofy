package com.restofy.kitchen.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage;
import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation;
import com.restofy.kitchen.domain.enums.ConfirmationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An Order service request for the kitchen's confirmation of a cancellation, and the kitchen's answer. It describes the
 * REQUEST, never the order: {@code status=APPROVED} means the kitchen agreed, not that the order is cancelled; the
 * Order service decides after the answer. The answer fields stay {@code null} until a staff member answers.
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
		String rejectionCode) {

	public static OrderCancellationConfirmationResponse from(OrderCancellationConfirmation confirmation,
			List<OrderCancellationRequestedMessage.Item> items) {
		return new OrderCancellationConfirmationResponse(confirmation.getCancellationId(), confirmation.getOrderId(),
				confirmation.getScope(), items, confirmation.getRequestedAt(), confirmation.getStatus(), confirmation.getAnsweredAt(),
				confirmation.getAnsweredByUserId(), confirmation.getRejectionCode());
	}
}
