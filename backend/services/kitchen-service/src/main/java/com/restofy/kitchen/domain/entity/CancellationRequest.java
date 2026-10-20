package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
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
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The kitchen's request to cancel a whole order. It is a REQUEST: creating one changes neither the kitchen order's
 * preparation status nor anything the Order service owns. What was asked (who, why, when) never changes afterwards;
 * only the Order service's decision can later be added.
 */
@Entity
@Table(name = "cancellation_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CancellationRequest implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "command_id", nullable = false, updatable = false)
	private UUID commandId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CancellationRequestStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", nullable = false, updatable = false, length = 40)
	private CancellationReason reasonCode;

	@Column(name = "reason_text", updatable = false, length = 500)
	private String reasonText;

	@Column(name = "requested_by_user_id", nullable = false, updatable = false)
	private UUID requestedByUserId;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "decision_reason_code", length = 60)
	private String decisionReasonCode;

	@Version
	private Long version;

	@Transient
	private boolean isNew = true;

	/** Everything the client asked for; the restaurant, the user and the time come from the verified caller and the server. */
	public record NewRequest(UUID restaurantId, UUID orderId, UUID commandId, CancellationReason reasonCode, String reasonText,
			UUID requestedByUserId, Instant requestedAt) {
	}

	public static CancellationRequest open(NewRequest data) {
		CancellationRequest request = new CancellationRequest();
		request.id = UUID.randomUUID();
		request.restaurantId = data.restaurantId();
		request.orderId = data.orderId();
		request.commandId = data.commandId();
		request.status = CancellationRequestStatus.REQUESTED;
		request.reasonCode = data.reasonCode();
		request.reasonText = data.reasonText();
		request.requestedByUserId = data.requestedByUserId();
		request.requestedAt = data.requestedAt();
		return request;
	}

	/** Is this exactly the request that is being sent again (same order, reason, text and user)? */
	public boolean isSameRequest(UUID orderId, CancellationReason reasonCode, String reasonText, UUID requestedByUserId) {
		return this.orderId.equals(orderId) && this.reasonCode == reasonCode && Objects.equals(this.reasonText, reasonText)
				&& this.requestedByUserId.equals(requestedByUserId);
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
