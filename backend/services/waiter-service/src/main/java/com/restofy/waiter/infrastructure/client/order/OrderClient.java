package com.restofy.waiter.infrastructure.client.order;

import com.restofy.waiter.domain.enums.OrderView;
import com.restofy.waiter.domain.enums.PreparationStatus;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.infrastructure.client.DownstreamCalls;
import com.restofy.waiter.infrastructure.config.ServiceUrlProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter for the Order service, the owner of orders, snapshots and cancellations. Calls are read-only; the
 * POST endpoints are searches that only carry large filters in a body. The contract is a DRAFT
 * (docs/waiter/integration-contracts.md); Order has not implemented it yet.
 */
@Component
public class OrderClient {

    public static final String DEPENDENCY = "order";
    /** Upper bound of session ids in one request; equals the table snapshot limit of the QR adapter. */
    public static final int MAX_SESSION_IDS = 500;

    private final RestClient client;

    public OrderClient(RestClient.Builder builder, ServiceUrlProperties urls) {
        this.client = builder.clone().baseUrl(urls.orderUrl().toString()).build();
    }

    /** Active order count per live session in one request; sessions without active orders map to zero. */
    public Map<UUID, Integer> activeOrderCounts(UUID restaurantId, Collection<UUID> tableSessionIds) {
        if (tableSessionIds.isEmpty()) return Map.of();
        if (tableSessionIds.size() > MAX_SESSION_IDS) {
            throw new IllegalArgumentException("Too many session ids for one request");
        }
        Set<UUID> requested = new HashSet<>(tableSessionIds);
        SessionSummariesDto result = DownstreamCalls.execute(DEPENDENCY, () -> client.post()
                .uri("/internal/v1/orders/session-summaries")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SessionSummaryRequest(restaurantId, List.copyOf(requested)))
                .retrieve().body(SessionSummariesDto.class));
        Map<UUID, Integer> counts = new HashMap<>();
        for (SessionSummariesDto.Entry entry : result.items()) {
            if (!requested.contains(entry.tableSessionId())) throw badResponse("summary for a session that was not requested");
            if (counts.put(entry.tableSessionId(), entry.activeOrderCount()) != null) {
                throw badResponse("duplicate session summary");
            }
        }
        return counts;
    }

    /**
     * One page of orders. Filters and ordering are applied by Order to the whole data set; the answer is
     * checked against the request so a downstream that ignores a filter or the tenant is never shown.
     */
    public OrderPageDto search(UUID restaurantId, UUID tableId, Collection<UUID> tableSessionIds, OrderView view,
                               int page, int size) {
        if (tableSessionIds != null && (tableSessionIds.isEmpty() || tableSessionIds.size() > MAX_SESSION_IDS)) {
            throw new IllegalArgumentException("tableSessionIds must hold between 1 and " + MAX_SESSION_IDS + " ids");
        }
        List<UUID> sessions = tableSessionIds == null ? null : List.copyOf(new HashSet<>(tableSessionIds));
        OrderPageDto result = DownstreamCalls.execute(DEPENDENCY, () -> client.post()
                .uri("/internal/v1/orders/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OrderSearchRequest(restaurantId, tableId, sessions, view, page, size))
                .retrieve().body(OrderPageDto.class));
        if (result.page() != page || result.size() != size || result.items().size() > size
                || result.totalElements() < result.items().size()) {
            throw badResponse("page metadata does not match the request");
        }
        Set<UUID> ids = new HashSet<>();
        for (OrderDto order : result.items()) {
            requireTenant(restaurantId, order);
            if (!ids.add(order.id())) throw badResponse("duplicate order in the page");
            if (tableId != null && !tableId.equals(order.tableId())) throw badResponse("order of another table");
            if (sessions != null && !sessions.contains(order.tableSessionId())) {
                throw badResponse("order of a session that was not requested");
            }
            if (view != null && !view.matches(order.businessStatus(), preparationOf(order), order.deliveryStatus())) {
                throw badResponse("order does not match the requested view");
            }
        }
        return result;
    }

    public Optional<OrderDto> findOrder(UUID restaurantId, UUID orderId) {
        Optional<OrderDto> order = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(uri -> uri.path("/internal/v1/orders/{orderId}")
                        .queryParam("restaurantId", restaurantId).build(orderId))
                .retrieve().body(OrderDto.class));
        order.ifPresent(found -> {
            requireTenant(restaurantId, found);
            if (!found.id().equals(orderId)) throw badResponse("order id does not match the request");
        });
        return order;
    }

    private static PreparationStatus preparationOf(OrderDto order) {
        return order.preparation() == null ? null : order.preparation().status();
    }

    private static void requireTenant(UUID restaurantId, OrderDto order) {
        if (!restaurantId.equals(order.restaurantId())) throw badResponse("order belongs to another restaurant");
    }

    private static DependencyException badResponse(String detail) {
        return new DependencyException(DEPENDENCY, DependencyFailure.BAD_RESPONSE,
                "order service returned an unusable response: " + detail);
    }
}
