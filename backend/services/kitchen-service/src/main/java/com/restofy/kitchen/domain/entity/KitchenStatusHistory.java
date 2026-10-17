package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kitchen_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KitchenStatusHistory implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "kitchen_order_id", nullable = false, updatable = false)
	private UUID kitchenOrderId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", updatable = false, length = 20)
	private KitchenOrderStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, updatable = false, length = 20)
	private KitchenOrderStatus toStatus;

	@Column(name = "actor_user_id", updatable = false)
	private UUID actorUserId;

	@Column(name = "source_event_id", updatable = false)
	private UUID sourceEventId;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(updatable = false)
	private String reason;

	@Transient
	private boolean isNew = true;

	/** Initial entry written when an accepted order is first received (no previous status). */
	public static KitchenStatusHistory initial(KitchenOrder order, UUID sourceEventId, Instant occurredAt) {
		KitchenStatusHistory history = new KitchenStatusHistory();
		history.id = UUID.randomUUID();
		history.restaurantId = order.getRestaurantId();
		history.kitchenOrderId = order.getId();
		history.toStatus = order.getStatus();
		history.sourceEventId = sourceEventId;
		history.occurredAt = occurredAt;
		return history;
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
