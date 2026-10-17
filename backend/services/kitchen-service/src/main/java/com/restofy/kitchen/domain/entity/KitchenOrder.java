package com.restofy.kitchen.domain.entity;

import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Kitchen read-model of an order accepted by the Order service. Item data is a read-only snapshot. */
@Entity
@Table(name = "kitchen_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KitchenOrder {

	@Id
	private UUID id;

	@Column(name = "restaurant_id", nullable = false, updatable = false)
	private UUID restaurantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "table_session_id", nullable = false, updatable = false)
	private UUID tableSessionId;

	@Column(name = "table_number_snapshot", nullable = false, updatable = false, length = 40)
	private String tableNumberSnapshot;

	@Column(name = "order_number_snapshot", nullable = false, updatable = false, length = 40)
	private String orderNumberSnapshot;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private KitchenOrderStatus status;

	@Column(name = "received_at", nullable = false, updatable = false)
	private Instant receivedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "ready_at")
	private Instant readyAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancellation_reason")
	private String cancellationReason;

	@Column(name = "source_order_version", nullable = false, updatable = false)
	private long sourceOrderVersion;

	@Column(name = "source_event_id", nullable = false, updatable = false)
	private UUID sourceEventId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	@OneToMany(mappedBy = "kitchenOrder", cascade = CascadeType.ALL)
	@OrderBy("lineNumber ASC")
	@BatchSize(size = 100)
	private List<KitchenOrderItem> items = new ArrayList<>();

	/** First receipt of an accepted order: always starts as WAITING. */
	public static KitchenOrder receive(UUID restaurantId, UUID orderId, UUID tableSessionId, String tableNumber,
			String orderNumber, Instant receivedAt, long sourceOrderVersion, UUID sourceEventId, Instant now) {
		KitchenOrder order = new KitchenOrder();
		order.id = UUID.randomUUID();
		order.restaurantId = restaurantId;
		order.orderId = orderId;
		order.tableSessionId = tableSessionId;
		order.tableNumberSnapshot = tableNumber;
		order.orderNumberSnapshot = orderNumber;
		order.status = KitchenOrderStatus.WAITING;
		order.receivedAt = receivedAt;
		order.sourceOrderVersion = sourceOrderVersion;
		order.sourceEventId = sourceEventId;
		order.createdAt = now;
		order.updatedAt = now;
		return order;
	}

	public KitchenOrderItem addItem(int lineNumber, UUID orderItemId, UUID productId, String productName, int quantity,
			String optionsSnapshot, String note) {
		KitchenOrderItem item = new KitchenOrderItem(this, lineNumber, orderItemId, productId, productName, quantity,
				optionsSnapshot, note);
		items.add(item);
		return item;
	}

	@PreUpdate
	void touch() {
		updatedAt = Instant.now();
	}
}
