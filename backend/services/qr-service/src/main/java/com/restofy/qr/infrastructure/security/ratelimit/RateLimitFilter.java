package com.restofy.qr.infrastructure.security.ratelimit;

import com.restofy.qr.domain.exception.RateLimitExceededException;
import com.restofy.qr.infrastructure.security.StaffRequestFilter;
import com.restofy.qr.infrastructure.security.guest.GuestCookies;
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
 * Limits that need a verified QR code or table run later, in the services.
 */
public final class RateLimitFilter extends OncePerRequestFilter {
    private static final String PREFIX = "/api/v1/qr";
    private final QrRateLimits limits;
    private final ClientAddressResolver clients;
    private final GuestCookies cookies;

    public RateLimitFilter(QrRateLimits limits, ClientAddressResolver clients, GuestCookies cookies) {
        this.limits = limits;
        this.clients = clients;
        this.cookies = cookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            check(request);
        } catch (RateLimitExceededException limited) {
            tooManyRequests(response, limited.retryAfterSeconds());
            return;
        } catch (RateLimitUnavailableException _) {
            unavailable(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void check(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (GuestRequestFilter.guestPath(method, path)) {
            switch (path) {
                case PREFIX + "/validate" -> limits.scanFrom(clients.resolve(request));
                case PREFIX + "/table-sessions/open" -> limits.openFrom(clients.resolve(request));
                // The guest filter already required exactly one well-formed credential cookie on these routes.
                default -> limits.guestCall(clients.resolve(request), cookies.guestToken(request),
                        "POST".equals(method));
            }
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof StaffRequestFilter.Principal staff) {
            limits.staff(staff.restaurantId(), staff.userId(), !"GET".equals(method));
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
