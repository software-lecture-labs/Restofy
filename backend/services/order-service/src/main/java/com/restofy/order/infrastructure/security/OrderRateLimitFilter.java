package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.security.OrderRateLimitProperties.Operation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Only authorised application routes are counted. Recovery and payment routes are outside the allowlist. */
final class OrderRateLimitFilter extends OncePerRequestFilter {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    private static final String CART = "/api/v1/carts/current";
    private static final String ORDERS = "/api/v1/orders";
    private static final String ORDER = ORDERS + "/*";
    private static final String KITCHEN = "/api/v1/internal/order-events/kitchen";
    private static final List<Route> ROUTES = List.of(
            new Route(Operation.CART_READ, read(CART)),
            new Route(Operation.CART_WRITE, new OrRequestMatcher(match(HttpMethod.POST, CART + "/items"),
                    match(HttpMethod.PATCH, CART + "/items/*"), match(HttpMethod.DELETE, CART + "/items"),
                    match(HttpMethod.DELETE, CART + "/items/*"))),
            new Route(Operation.CHECKOUT, match(HttpMethod.POST, ORDERS)),
            new Route(Operation.ORDER_READ, new OrRequestMatcher(read(ORDERS), read(ORDER),
                    read(ORDER + "/history"), read(ORDER + "/cancellations"),
                    match(HttpMethod.GET, "/api/v1/admin/order-cancellations"), WaiterAuthenticationFilter::isReadRoute)),
            new Route(Operation.CANCELLATION, new OrRequestMatcher(match(HttpMethod.POST, ORDER + "/cancellations"),
                    match(HttpMethod.PATCH, ORDER + "/cancellations/*/reason"))),
            new Route(Operation.STAFF_STATUS, match(HttpMethod.PATCH, ORDER + "/fulfillment-status")),
            new Route(Operation.QR_ORDER_STATE, match(HttpMethod.GET, "/api/v1/internal/table-sessions/*/order-state")),
            new Route(Operation.KITCHEN_STATUS, match(HttpMethod.POST, KITCHEN)),
            new Route(Operation.KITCHEN_CANCELLATION, match(HttpMethod.POST, KITCHEN + "-cancellation-request")),
            new Route(Operation.KITCHEN_DECISION, match(HttpMethod.POST, KITCHEN + "-cancellation")),
            new Route(Operation.WAITER_SERVE, match(HttpMethod.POST, WaiterAuthenticationFilter.SERVE_PATH))
    );

    private final OrderRateLimitProperties properties;
    private final OrderRateLimitStore store;
    private final ApiErrorWriter errors;

    OrderRateLimitFilter(OrderRateLimitProperties properties, OrderRateLimitStore store, ApiErrorWriter errors) {
        this.properties = properties;
        this.store = store;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Operation operation = ROUTES.stream().filter(route -> route.matcher().matches(request))
                .map(Route::operation).findFirst().orElse(null);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (operation == null || authentication == null || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        boolean delegatedRead = operation == Operation.ORDER_READ && authentication.getAuthorities().stream()
                .anyMatch(authority -> WaiterAuthenticationFilter.READ_AUTHORITY.equals(authority.getAuthority()));
        if (delegatedRead) {
            operation = Operation.WAITER_READ;
        }
        Scope scope = scope(authentication.getPrincipal(), operation);
        if (scope == null) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfter;
        try {
            String key = SignedServiceRequest.digest(scope.identity().getBytes(StandardCharsets.UTF_8));
            retryAfter = store.hit(scope.kind() + ":" + operation.name(), key, properties.limits().get(operation),
                    properties.windowSeconds(), properties.maxBucketsPerOperation());
        } catch (DataAccessException | TransactionException failure) {
            errors.write(response, ErrorCode.DEPENDENCY_UNAVAILABLE, "Request rate limits are currently unavailable");
            return;
        }
        if (retryAfter > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            errors.write(response, ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests. Retry later.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static Scope scope(Object principal, Operation operation) {
        return switch (principal) {
            case GuestSessionContext guest -> new Scope("guest", guest.restaurantId() + ":" + guest.guestSessionId());
            case StaffActor staff when operation == Operation.WAITER_READ || operation == Operation.WAITER_SERVE ->
                    new Scope("waiter", staff.restaurantId().toString());
            case StaffActor staff -> new Scope("staff", staff.restaurantId() + ":" + staff.userId());
            case ServiceActor service -> new Scope("service", service.name() + ":" +
                    (service.signedRequest() == null ? "" : service.signedRequest().restaurantId()));
            case null, default -> null;
        };
    }

    private static RequestMatcher read(String path) {
        return new OrRequestMatcher(match(HttpMethod.GET, path), match(HttpMethod.HEAD, path));
    }

    private static RequestMatcher match(HttpMethod method, String path) {
        return PATHS.matcher(method, path);
    }

    private record Route(Operation operation, RequestMatcher matcher) { }
    private record Scope(String kind, String identity) { }
}
