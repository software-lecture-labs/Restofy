package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A staff order search. Every field is a filter and optional; the restaurant never comes from the body, it is the
 * caller's own, taken from the verified staff identity. {@code view} and {@code fulfillmentStatuses} select orders by the
 * fulfillment status and cannot be combined: the view is one of three fixed sets, the status list is an explicit one
 * (READY alone for a delivery queue, READY, SERVED and COMPLETED for a history).
 *
 * <p>Paging and ordering are those of the plain order list: {@code page} is 0-based, {@code limit} 1 to 100, {@code sort}
 * {@code placedAt,desc} (default) or {@code placedAt,asc}; their values are validated by the query itself so a bad one
 * is a 422 that names the parameter.
 */
public record OrderSearchRequest(
        @Size(min = 1, max = InputLimits.MAX_TABLE_SESSION_IDS) List<@NotNull UUID> tableSessionIds,
        UUID tableId,
        @Size(max = InputLimits.MAX_STATUS_NAME_LENGTH) String view,
        @Size(min = 1, max = InputLimits.MAX_FULFILLMENT_STATUSES)
        List<@NotBlank @Size(max = InputLimits.MAX_STATUS_NAME_LENGTH) String> fulfillmentStatuses,
        @Size(max = InputLimits.MAX_STATUS_NAME_LENGTH) String paymentStatus,
        Integer page,
        Integer limit,
        @Size(max = InputLimits.MAX_STATUS_NAME_LENGTH) String sort) {
}
