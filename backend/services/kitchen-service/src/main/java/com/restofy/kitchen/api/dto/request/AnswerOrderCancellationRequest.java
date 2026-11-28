package com.restofy.kitchen.api.dto.request;

import com.restofy.kitchen.domain.enums.OrderDecision;
import jakarta.validation.constraints.NotNull;

/**
 * The kitchen's answer to an Order service cancellation request: APPROVED or REJECTED, nothing else. Restaurant, user and
 * time come from the verified caller and the server. No free text: the Order service and the guest could read it.
 */
public record AnswerOrderCancellationRequest(@NotNull OrderDecision decision) {
}
