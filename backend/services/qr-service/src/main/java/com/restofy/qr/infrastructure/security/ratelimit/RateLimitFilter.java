package com.restofy.qr.infrastructure.security.ratelimit;

import com.restofy.qr.domain.exception.RateLimitExceededException;
import com.restofy.qr.infrastructure.security.StaffRequestFilter;
import com.restofy.qr.infrastructure.security.guest.GuestRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the request-level QR limits (ADR-QR-002 K7). It runs after the staff and guest filters, so only requests
 * that passed their cheap format, Origin and CSRF checks are counted, and before any controller touches the database.
 * Limits that need a verified QR code, table or guest run later, in the services and controllers.
 *
 * <p>On guest routes the address budget counts failures only, and it never refuses a request by itself. It is read
 * before the request; when it is spent, {@link #FAILURE_RETRY_AFTER} tells the exception handler to answer an unknown
 * credential with 429 instead of 404. Known QR codes and guests are answered as usual. After the request, an unknown
 * credential ({@link #UNKNOWN_CREDENTIAL}) is counted while the budget is still open.
 */
public final class RateLimitFilter extends OncePerRequestFilter {
    /** Request attribute set when the QR code or guest credential of the request matched nothing of ours. */
    public static final String UNKNOWN_CREDENTIAL = RateLimitFilter.class.getName() + ".unknownCredential";
    /** Request attribute (a {@link Long} of seconds) set when the address's failure budget is spent. */
    public static final String FAILURE_RETRY_AFTER = RateLimitFilter.class.getName() + ".failureRetryAfter";
    private static final String PREFIX = "/api/v1/qr";
    private final QrRateLimits limits;
    private final ClientAddressResolver clients;

    public RateLimitFilter(QrRateLimits limits, ClientAddressResolver clients) {
        this.limits = limits;
        this.clients = clients;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String method = request.getMethod();
        String path = request.getRequestURI();
        QrRateLimits.FailureBudget budget = null;
        String peer = null;
        long retryAfter = 0;
        try {
            if (GuestRequestFilter.guestPath(method, path)) {
                budget = switch (path) {
                    case PREFIX + "/validate" -> QrRateLimits.FailureBudget.SCAN;
                    case PREFIX + "/table-sessions/open" -> QrRateLimits.FailureBudget.OPEN;
                    default -> QrRateLimits.FailureBudget.GUEST;
                };
                peer = clients.resolve(request);
                retryAfter = limits.failureRetryAfter(budget, peer);
                if (retryAfter > 0) {
                    request.setAttribute(FAILURE_RETRY_AFTER, retryAfter);
                }
            } else {
                staff(method);
            }
        } catch (RateLimitExceededException limited) {
            tooManyRequests(response, limited.retryAfterSeconds());
            return;
        } catch (RateLimitUnavailableException _) {
            unavailable(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // A spent budget is already full; counting more would only write rows.
            if (budget != null && retryAfter == 0 && Boolean.TRUE.equals(request.getAttribute(UNKNOWN_CREDENTIAL))) {
                chargeFailure(budget, peer);
            }
        }
    }

    private void staff(String method) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof StaffRequestFilter.Principal staff) {
            limits.staff(staff.restaurantId(), staff.userId(), !"GET".equals(method));
        }
    }

    private void chargeFailure(QrRateLimits.FailureBudget budget, String peer) {
        try {
            limits.failed(budget, peer);
        } catch (RateLimitUnavailableException _) {
            // The 404 is already written; the next request's budget check fails closed (503) while the store is down.
        }
    }

    static void tooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Too many requests. Retry later.\"}");
    }

    static void unavailable(HttpServletResponse response) throws IOException {
        response.setStatus(503);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"The request could not be accepted now. Retry later.\"}");
    }
}
