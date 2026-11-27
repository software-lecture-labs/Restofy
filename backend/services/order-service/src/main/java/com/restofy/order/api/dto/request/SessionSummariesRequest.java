package com.restofy.order.api.dto.request;

import com.restofy.order.domain.InputLimits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The table sessions whose active orders are counted. The restaurant never comes from the body: it is the caller's own,
 * taken from the verified staff identity, so an id of another restaurant simply counts nothing.
 */
public record SessionSummariesRequest(
        @NotEmpty @Size(max = InputLimits.MAX_TABLE_SESSION_IDS) List<@NotNull UUID> tableSessionIds) {
}
