package com.restofy.order.application.service;

import com.restofy.order.api.dto.response.OrderDetailResponse;
import com.restofy.order.api.dto.response.OrderHistoryResponse;
import com.restofy.order.application.mapper.OrderMapper;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.persistence.OrderStatusHistoryStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads one order and its history for the caller that is entitled to see it. The decision is made here (not only in
 * the HTTP layer) and always inside the caller's own restaurant: knowing an order id grants nothing.
 *
 * <p>Who may read what:
 * <ul>
 *   <li>GUEST: only their OWN orders (same guest session), and only while their table session is ACTIVE.</li>
 *   <li>STAFF ADMIN and WAITER: every order of their own restaurant. ADMIN also sees staff identifiers in the history.</li>
 *   <li>STAFF KITCHEN: nothing here; Kitchen receives the order through the OrderPlaced event (least privilege).</li>
 * </ul>
 * An order that does not exist, belongs to another restaurant, or to another guest is reported identically as not
 * found, so its existence is not revealed.
 */
@Service
public class OrderQueryService {

    static final Set<StaffRole> STAFF_READERS = Set.of(StaffRole.ADMIN, StaffRole.WAITER);

    private final OrderRepository orders;
    private final OrderStatusHistoryStore history;
    private final OrderMapper mapper;

    public OrderQueryService(OrderRepository orders, OrderStatusHistoryStore history, OrderMapper mapper) {
        this.orders = orders;
        this.history = history;
        this.mapper = mapper;
    }

    public OrderDetailResponse detail(RequestActor actor, UUID orderId) {
        return mapper.toDetail(load(actor, orderId));
    }

    public OrderHistoryResponse history(RequestActor actor, UUID orderId) {
        Order order = load(actor, orderId);
        boolean revealActorIds = actor instanceof RequestActor.Staff(var staff) && staff.role() == StaffRole.ADMIN;
        List<OrderHistoryResponse.Entry> entries = history.find(order.getRestaurantId(), order.getId()).stream()
                .map(entry -> new OrderHistoryResponse.Entry(entry.axis().name(), entry.fromStatus(),
                        entry.toStatus(), entry.actorType().name(), entry.actorRole(),
                        revealActorIds ? entry.actorId() : null, entry.source(), entry.reason(), entry.occurredAt()))
                .toList();
        return new OrderHistoryResponse(order.getId(), entries);
    }

    private Order load(RequestActor actor, UUID orderId) {
        return switch (actor) {
            case RequestActor.Guest(var guest) -> {
                if (!guest.isActive()) {
                    throw new BusinessException(ErrorCode.TABLE_SESSION_NOT_ACTIVE,
                            "The table session is no longer active; orders can no longer be viewed");
                }
                Order order = find(guest.restaurantId(), orderId);
                if (!order.getGuestSessionId().equals(guest.guestSessionId())) {
                    throw notFound();
                }
                yield order;
            }
            case RequestActor.Staff(var staff) -> {
                if (!STAFF_READERS.contains(staff.role())) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED, "This role may not view orders");
                }
                yield find(staff.restaurantId(), orderId);
            }
        };
    }

    private Order find(UUID restaurantId, UUID orderId) {
        return orders.findByIdAndRestaurantId(orderId, restaurantId).orElseThrow(OrderQueryService::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.ORDER_NOT_FOUND, "The order does not exist");
    }
}
