package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.OptionAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned contract for "order accepted" (schemaVersion 1). Not agreed end to end yet; it deliberately carries no
 * price or tax data because Kitchen never calculates money.
 *
 * <p>{@code tableId} is required and checked but not stored: Kitchen shows {@code tableNumber}, the number as it was
 * when the order was placed, and has no column for the id. A redelivery that differs only in {@code tableId} is
 * therefore not recognised as different content.
 *
 * <p>{@code eventType} is always {@value #TYPE}: the same Order channel is meant to carry other event types, which
 * this contract must never be read as. An item's options are the removed ingredients and the extras together; Order
 * accepts up to 50 of each.
 */
public record OrderAcceptedMessage(
		@NotNull UUID eventId,
		@NotNull @Pattern(regexp = OrderAcceptedMessage.TYPE) String eventType,
		@NotNull @Min(1) @Max(1) Integer schemaVersion,
		@NotNull Instant occurredAt,
		@Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]*") String correlationId,
		@NotNull UUID restaurantId,
		@NotNull UUID orderId,
		@NotNull @PositiveOrZero Long aggregateVersion,
		@NotBlank @Size(max = 40) String orderNumber,
		@NotNull UUID tableId,
		@NotNull UUID tableSessionId,
		@NotBlank @Size(max = 40) String tableNumber,
		@NotEmpty @Size(max = 200) List<@NotNull @Valid Item> items) {

	public static final String TYPE = "OrderAccepted";
	/** Order's own bound: 50 removed ingredients plus 50 extras on one line. */
	public static final int MAX_OPTIONS_PER_ITEM = 100;

	public record Item(
			@NotNull UUID orderItemId,
			@NotNull UUID productId,
			@NotBlank @Size(max = 200) String productName,
			@NotNull @Min(1) @Max(999) Integer quantity,
			@Size(max = 1000) String note,
			@Size(max = OrderAcceptedMessage.MAX_OPTIONS_PER_ITEM) List<@NotNull @Valid Option> options) {

		public Item {
			options = options == null ? List.of() : List.copyOf(options);
		}
	}

	public record Option(
			@NotNull OptionAction action,
			@NotNull UUID optionId,
			@NotBlank @Size(max = 150) String name,
			@NotNull @Min(1) @Max(99) Integer quantity,
			@Size(max = 500) String note) {
	}
}
