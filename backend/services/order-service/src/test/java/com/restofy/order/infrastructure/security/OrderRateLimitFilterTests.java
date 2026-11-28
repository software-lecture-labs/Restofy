package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.infrastructure.security.OrderRateLimitProperties.Operation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderRateLimitFilterTests {

    private final OrderRateLimitStore store = mock(OrderRateLimitStore.class);
    private final OrderRateLimitFilter filter = new OrderRateLimitFilter(properties(), store,
            new ApiErrorWriter(JsonMapper.builder().build()));
    private final StaffActor staff = new StaffActor(UUID.randomUUID(), UUID.randomUUID(), StaffRole.WAITER);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
            "POST,/api/v1/internal/table-sessions/session/closure-preparations,QR",
            "GET,/api/v1/internal/table-sessions/session/closure-preparations/operation,QR",
            "POST,/api/v1/internal/table-sessions/session/closure-preparations/operation/confirmation,QR",
            "POST,/api/v1/internal/order-events/payment,PAYMENT",
            "GET,/api/v1/internal/orders/order/serve-commands/command,WAITER"
    })
    void criticalRecoveryRoutesDoNotUseTheLimiter(String method, String path, String caller) throws Exception {
        authenticate(caller.equals("WAITER") ? waiter(WaiterAuthenticationFilter.SERVE_AUTHORITY)
                : service(ServiceName.valueOf(caller)));
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
        assertNotNull(chain.getRequest());
        assertNull(response.getHeader("Retry-After"));
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/v1/internal/table-sessions/session/order-state,QR,QR_ORDER_STATE",
            "POST,/api/v1/internal/order-events/kitchen,KITCHEN,KITCHEN_STATUS",
            "POST,/api/v1/internal/order-events/kitchen-cancellation-request,KITCHEN,KITCHEN_CANCELLATION",
            "POST,/api/v1/internal/order-events/kitchen-cancellation,KITCHEN,KITCHEN_DECISION",
            "POST,/api/v1/internal/orders/order/serve,WAITER,WAITER_SERVE",
            "POST,/api/v1/orders/search,WAITER,WAITER_READ",
            "POST,/api/v1/orders/session-summaries,WAITER,WAITER_READ",
            "POST,/api/v1/orders/search,STAFF,ORDER_READ",
            "PATCH,/api/v1/orders/order/fulfillment-status,STAFF,STAFF_STATUS",
            "POST,/api/v1/orders/order/cancellations,STAFF,CANCELLATION"
    })
    void separatesBudgetsByOperationAndVerifiedCaller(String method, String path, String caller, String operation)
            throws Exception {
        String namespace;
        switch (caller) {
            case "WAITER" -> {
                authenticate(waiter(operation.equals("WAITER_READ") ? WaiterAuthenticationFilter.READ_AUTHORITY
                        : WaiterAuthenticationFilter.SERVE_AUTHORITY));
                namespace = "waiter";
            }
            case "STAFF" -> {
                authenticate(new StaffAuthentication(staff));
                namespace = "staff";
            }
            default -> {
                authenticate(service(ServiceName.valueOf(caller)));
                namespace = "service";
            }
        }
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        String bucket = namespace + ":" + operation;
        when(store.hit(eq(bucket), anyString(), eq(2), eq(60), eq(100))).thenReturn(37L);
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
        assertEquals(429, response.getStatus());
        assertEquals("37", response.getHeader("Retry-After"));
        assertNull(chain.getRequest());
        verify(store).hit(eq(bucket), anyString(), eq(2), eq(60), eq(100));
    }

    @Test
    void storeFailureRejectsLimitedRequestsWith503() throws Exception {
        authenticate(new StaffAuthentication(staff));
        when(store.hit(eq("staff:ORDER_READ"), anyString(), eq(2), eq(60), eq(100)))
                .thenThrow(new DataAccessResourceFailureException("storage unavailable"));
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/orders"), response, chain);
        assertEquals(503, response.getStatus());
        assertNull(chain.getRequest());
    }

    private Authentication waiter(String authority) {
        return UsernamePasswordAuthenticationToken.authenticated(staff, null,
                AuthorityUtils.createAuthorityList(authority));
    }

    private static Authentication service(ServiceName service) {
        return new ServiceAuthentication(new ServiceActor(service));
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static OrderRateLimitProperties properties() {
        var limits = new EnumMap<Operation, Integer>(Operation.class);
        for (Operation operation : Operation.values()) {
            limits.put(operation, 2);
        }
        return new OrderRateLimitProperties(60, 100, limits);
    }
}
