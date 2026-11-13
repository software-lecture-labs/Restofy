package com.restofy.order.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * What the Order Service made of the Kitchen's own cancellation request. The request being received never cancels by
 * itself: {@code outcome} APPLIED means the Order Service checked its rules and cancelled; REJECTED means it checked them
 * and did not ({@code rejectionCode}), and the refusal is recorded. {@code duplicate=true}: this event id was handled
 * before; {@code cancellationId} is then absent if the original record is no longer retrievable from the idempotency
 * window, but the outcome is the original one and nothing was done twice.
 *
 * <p>{@code orderVersion} is the version of the order right after the decision (an approval moves it up, a refusal leaves
 * it as it was) and {@code decidedAt} the moment of the decision. A repeat of the same request returns the FIRST
 * decision's values, whatever happened to the order since. Both are absent only when the record of the first decision is
 * gone (the same case as {@code cancellationId}) or the decision was taken before the version was recorded.
 */
public record KitchenCancellationRequestResponse(String outcome, boolean duplicate, UUID orderId, UUID cancellationId,
                                                 String scope, String rejectionCode, String fulfillmentStatus,
                                                 String paymentStatus, Long orderVersion, Instant decidedAt) {
}
