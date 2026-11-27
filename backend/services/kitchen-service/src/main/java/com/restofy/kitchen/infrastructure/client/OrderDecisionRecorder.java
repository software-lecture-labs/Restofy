package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.application.contract.OrderCancellationDecisionMessage;
import com.restofy.kitchen.application.service.OrderDecisionService;

/**
 * Records the Order service's decision on Kitchen's own cancellation request, read from the Order service's answer.
 * Production: {@code OrderDecisionService#apply} with the Order service as the verified sender (the answer arrived on
 * the authenticated HTTPS connection to the configured Order address).
 */
@FunctionalInterface
public interface OrderDecisionRecorder {

	OrderDecisionService.DecisionResult record(OrderCancellationDecisionMessage decision);
}
