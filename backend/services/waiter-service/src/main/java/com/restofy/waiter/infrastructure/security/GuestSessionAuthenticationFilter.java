package com.restofy.waiter.infrastructure.security;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.client.qr.QrGuestContextDto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Authenticates the one customer-facing request, {@code POST /api/v1/waiters/calls}. The guest credential is
 * the HttpOnly {@value #COOKIE} cookie that QR issued (ADR-QR-002 K3/K4). QR verifies it on every use, and the
 * answer, not the request, decides restaurant, table, table session and guest session. Nothing else in the
 * request is trusted.
 *
 * <p>This is not a public endpoint: without a credential that QR accepts the request is a 401. QR does not say
 * why it refuses a guest (unknown, ended, expired, or a table session that is no longer open), so neither does
 * Waiter. The credential is never logged or stored. Because a browser
 * attaches a cookie on its own, the request is protected by {@link BrowserCsrfProtection} first (Origin plus
 * the {@code X-Guest-CSRF} double submit, ADR-QR-002 K6). The former {@code X-Guest-Token} header is no longer a
 * credential: it is ignored, so a request that only carries it stays a 401. A staff cookie never authenticates
 * a guest and the guest cookie never authenticates staff.
 *
 * <p>The filter is created by the security configuration and is not a Spring bean, otherwise Boot would also
 * register it as a global servlet filter.
 */
final class GuestSessionAuthenticationFilter extends OncePerRequestFilter {

    static final String COOKIE = "restofy_guest";
    @SuppressWarnings("java:S1075") // Waiter's own public route; the filter must match this exact path.
    static final String CALLS_PATH = "/api/v1/waiters/calls";
    /** The token QR issues is 32 random bytes, Base64url without padding; anything else is not sent to QR. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final QrClient qr;
    private final HandlerExceptionResolver errors;

    GuestSessionAuthenticationFilter(QrClient qr, HandlerExceptionResolver errors) {
        this.qr = qr;
        this.errors = errors;
    }

    static boolean isGuestCallRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && CALLS_PATH.equals(request.getRequestURI());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isGuestCallRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        List<String> tokens = guestCookies(request);
        if (tokens.isEmpty() || (existing != null && !(existing instanceof AnonymousAuthenticationToken))) {
            chain.doFilter(request, response);
            return;
        }
        if (tokens.size() != 1 || !TOKEN.matcher(tokens.getFirst()).matches()) {
            errors.resolveException(request, response, null, new BadCredentialsException("Invalid guest credential"));
            return;
        }
        Optional<QrGuestContextDto> context;
        try {
            context = qr.verifyGuest(tokens.getFirst());
        } catch (DependencyException failure) {
            errors.resolveException(request, response, null, failure);
            return;
        }
        if (context.isEmpty()) {
            errors.resolveException(request, response, null, new BadCredentialsException("Unknown guest credential"));
            return;
        }
        // QR answers 200 only for an active guest of an open table session; the DTO rejects any other state.
        QrGuestContextDto verified = context.get();
        AuthenticatedGuest guest = new AuthenticatedGuest(verified.restaurantId(), verified.tableId(),
                verified.tableSessionId(), verified.guestSessionId());
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(guest, null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
        SecurityContextHolder.setContext(securityContext);
        chain.doFilter(request, response);
    }

    private static List<String> guestCookies(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return List.of();
        return Arrays.stream(cookies).filter(cookie -> COOKIE.equals(cookie.getName())).map(Cookie::getValue).toList();
    }
}
