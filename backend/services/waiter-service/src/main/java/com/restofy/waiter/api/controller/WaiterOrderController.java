package com.restofy.waiter.api.controller;

import com.restofy.waiter.api.dto.response.OrderResponse;
import com.restofy.waiter.api.dto.response.PageResponse;
import com.restofy.waiter.application.service.OrderFilter;
import com.restofy.waiter.application.service.OrderQueryService;
import com.restofy.waiter.application.service.PageParams;
import com.restofy.waiter.domain.enums.OrderSessionScope;
import com.restofy.waiter.domain.enums.OrderView;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only order endpoints of the waiter panel. Protected: WAITER role of the caller's restaurant. */
@RestController
@RequestMapping("/api/v1/waiters/orders")
public class WaiterOrderController {

    private final OrderQueryService orders;

    public WaiterOrderController(OrderQueryService orders) {
        this.orders = orders;
    }

    @GetMapping
    public PageResponse<OrderResponse> list(
            @AuthenticationPrincipal AuthenticatedStaff staff,
            @RequestParam(required = false) OrderView view,
            @RequestParam(required = false) UUID tableId,
            @RequestParam(required = false) OrderSessionScope sessionScope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageParams.DEFAULT_SIZE) int size,
            HttpServletRequest request) {
        QueryParameters.allow(request, "view", "tableId", "sessionScope", "page", "size");
        return orders.listOrders(staff, new OrderFilter(view, tableId, sessionScope), new PageParams(page, size));
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal AuthenticatedStaff staff, @PathVariable UUID orderId,
                             HttpServletRequest request) {
        QueryParameters.allow(request);
        return orders.getOrder(staff, orderId);
    }
}
