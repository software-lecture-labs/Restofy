package com.restofy.waiter.application.service;

import com.restofy.waiter.api.dto.response.OrderResponse;
import com.restofy.waiter.api.dto.response.PageResponse;
import com.restofy.waiter.application.mapper.WaiterMapper;
import com.restofy.waiter.domain.enums.OrderSessionScope;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.domain.exception.ResourceNotFoundException;
import com.restofy.waiter.infrastructure.client.order.OrderClient;
import com.restofy.waiter.infrastructure.client.order.OrderDto;
import com.restofy.waiter.infrastructure.client.order.OrderPageDto;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.client.qr.QrSessionDto;
import com.restofy.waiter.infrastructure.client.qr.QrTableDto;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Order queries. Filtering and ordering happen in Order over the whole data set, so totals are correct; Waiter
 * only scopes the query to the caller's restaurant and (by default) to the live table sessions reported by QR,
 * and enriches rows with table data from one QR snapshot.
 */
@Service
public class OrderQueryService {

    private final QrClient qr;
    private final OrderClient orders;

    public OrderQueryService(QrClient qr, OrderClient orders) {
        this.qr = qr;
        this.orders = orders;
    }

    public PageResponse<OrderResponse> listOrders(AuthenticatedStaff staff, OrderFilter filter, PageParams page) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        List<QrTableDto> tables = qr.listAllTables(restaurantId);
        Map<UUID, QrTableDto> tablesById = tables.stream().collect(Collectors.toMap(QrTableDto::id, Function.identity()));
        UUID tableId = filter.tableId();
        if (tableId != null && !tablesById.containsKey(tableId)) {
            throw new ResourceNotFoundException("TABLE_NOT_FOUND", "Table not found");
        }
        List<UUID> liveSessions = null;
        if (filter.scope() == OrderSessionScope.CURRENT) {
            liveSessions = tables.stream().filter(table -> tableId == null || tableId.equals(table.id()))
                    .map(QrTableDto::currentSession).filter(Objects::nonNull).map(QrSessionDto::id).toList();
            if (liveSessions.isEmpty()) return PageResponse.of(List.of(), page.page(), page.size(), 0);
        }
        OrderPageDto result = orders.search(restaurantId, tableId, liveSessions, filter.view(), page.page(), page.size());
        return PageResponse.of(result.items().stream()
                .map(order -> WaiterMapper.toOrder(order, knownTable(tablesById, order))).toList(),
                page.page(), page.size(), result.totalElements());
    }

    public OrderResponse getOrder(AuthenticatedStaff staff, UUID orderId) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        OrderDto order = orders.findOrder(restaurantId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
        QrTableDto table = qr.findTable(restaurantId, order.tableId())
                .orElseThrow(() -> unknownTable());
        return WaiterMapper.toOrder(order, table);
    }

    private static QrTableDto knownTable(Map<UUID, QrTableDto> tablesById, OrderDto order) {
        QrTableDto table = tablesById.get(order.tableId());
        if (table == null) throw unknownTable();
        return table;
    }

    private static DependencyException unknownTable() {
        return new DependencyException(OrderClient.DEPENDENCY, DependencyFailure.BAD_RESPONSE,
                "order service returned an unusable response: order of an unknown table");
    }
}
