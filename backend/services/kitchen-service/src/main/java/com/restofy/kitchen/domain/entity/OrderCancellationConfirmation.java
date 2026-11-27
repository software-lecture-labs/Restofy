package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.ConfirmationStatus;
import com.restofy.kitchen.domain.enums.OrderDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The Order service asks whether an order in preparation may be cancelled; the kitchen answers once. This is neither a
 * cancellation nor Kitchen's own request: the kitchen order keeps its status, and the Order service decides after the
 * answer. What was asked never changes, and an answer is final (the database enforces both).
 */
@Entity
@Table(name = "order_cancellation_confirmations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCancellationConfirmation implements Persistable<UUID> {

	/**
	 * The code sent with a refusal. The Order service requires one; it names this one as an example. Kitchen has no
	 * catalogue of refusal reasons, so the staff member chooses only between yes and no.
	 */
	public static final String KITCHEN_DECLINED = "KITCHEN_DECLINED";

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "cancellation_id", nullable = false, updatable = false)
	private UUID cancellationId;

	@Column(name = "request_event_id", nullable = false, updatable = false)
	private UUID requestEventId;

	@Column(nullable = false, updatable = false, length = 20)
	private String scope;

	/** What was asked, as {@code [{"orderItemId": ..., "quantity": ...}]}. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false, columnDefinition = "jsonb")
	private String items;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Column(name = "received_at", nullable = false, updatable = false)
	private Instant receivedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConfirmationStatus status;

	@Column(name = "answer_command_id")
	private UUID answerCommandId;

	@Column(name = "answered_by_user_id")
	private UUID answeredByUserId;

	@Column(name = "answered_at")
	private Instant answeredAt;

	@Column(name = "rejection_code", length = 40)
	private String rejectionCode;

	@Version
	private Long version;

	@Transient
	private boolean isNew = true;

	/** What the Order service asked; {@code items} is its JSON form. */
	public record Request(UUID restaurantId, UUID orderId, UUID cancellationId, UUID eventId, String scope, String items,
			Instant requestedAt) {
	}

	public static OrderCancellationConfirmation awaiting(Request request, Instant now) {
		OrderCancellationConfirmation confirmation = new OrderCancellationConfirmation();
		confirmation.id = UUID.randomUUID();
		confirmation.restaurantId = request.restaurantId();
		confirmation.orderId = request.orderId();
		confirmation.cancellationId = request.cancellationId();
		confirmation.requestEventId = request.eventId();
		confirmation.scope = request.scope();
		confirmation.items = request.items();
		confirmation.requestedAt = request.requestedAt().truncatedTo(ChronoUnit.MICROS);
		confirmation.receivedAt = now.truncatedTo(ChronoUnit.MICROS);
		confirmation.status = ConfirmationStatus.AWAITING_KITCHEN;
		return confirmation;
	}

	/** The kitchen's one answer. A refusal carries {@link #KITCHEN_DECLINED}. */
	public void answer(OrderDecision decision, UUID commandId, UUID userId, Instant now) {
		if (status != ConfirmationStatus.AWAITING_KITCHEN) {
			throw new IllegalStateException("An answered confirmation request is final");
		}
		boolean approved = decision == OrderDecision.APPROVED;
		status = approved ? ConfirmationStatus.APPROVED : ConfirmationStatus.REJECTED;
		rejectionCode = approved ? null : KITCHEN_DECLINED;
		answerCommandId = commandId;
		answeredByUserId = userId;
		answeredAt = now.truncatedTo(ChronoUnit.MICROS);
	}

	/** Is this exactly what the same event asked before (a redelivery)? */
	public boolean isSameRequest(Request request) {
		return orderId.equals(request.orderId()) && cancellationId.equals(request.cancellationId())
				&& scope.equals(request.scope()) && requestedAt.equals(request.requestedAt().truncatedTo(ChronoUnit.MICROS));
	}

	/** Is this the answer that the same command gave before (same request, decision and person)? */
	public boolean isAnswerOf(UUID commandId, UUID orderId, UUID cancellationId, OrderDecision decision, UUID userId) {
		ConfirmationStatus expected = decision == OrderDecision.APPROVED ? ConfirmationStatus.APPROVED : ConfirmationStatus.REJECTED;
		return commandId.equals(answerCommandId) && this.orderId.equals(orderId) && this.cancellationId.equals(cancellationId)
				&& status == expected && userId.equals(answeredByUserId);
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		isNew = false;
	}
}
