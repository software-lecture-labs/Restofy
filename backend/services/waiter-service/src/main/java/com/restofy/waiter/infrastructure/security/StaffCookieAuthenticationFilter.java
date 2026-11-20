package com.restofy.waiter.infrastructure.security;

import com.restofy.waiter.domain.exception.DependencyException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates staff requests ({@code /api/v1/waiters/**} except the customer's call) from Auth's HttpOnly
 * {@code restofy_access} cookie. {@link StaffSessionVerifier} proves the cookie locally and confirms the session
 * with Auth on every request; the answer, never the request, decides who the staff member, restaurant and role
 * are. Headers such as {@code X-Restaurant-Id} or {@code X-Role} are not read.
 *
 * <p>No cookie leaves the request anonymous (a 401 later). A repeated, malformed, expired, revoked or
 * idle-expired credential is a 401 right here. Auth being unable to answer is a dependency error (never "valid",
 * never "invalid"). A guest credential never reaches this filter and a staff cookie never grants a guest role.
 *
 * <p>Created by the security configuration and not a Spring bean, otherwise Boot would also register it as a
 * global servlet filter.
 */
final class StaffCookieAuthenticationFilter extends OncePerRequestFilter {

    private static final String STAFF_PREFIX = "/api/v1/waiters";

    private final StaffSessionVerifier sessions;
    private final HandlerExceptionResolver errors;

    StaffCookieAuthenticationFilter(StaffSessionVerifier sessions, HandlerExceptionResolver errors) {
        this.sessions = sessions;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean staffPath = path.equals(STAFF_PREFIX) || path.startsWith(STAFF_PREFIX + "/");
        return !staffPath || GuestSessionAuthenticationFilter.isGuestCallRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> credentials = accessCookies(request);
        if (credentials.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (credentials.size() != 1) {
            reject(request, response);
            return;
        }
        Optional<AuthenticatedStaff> staff;
        try {
            staff = sessions.verify(credentials.getFirst());
        } catch (DependencyException failure) {
            errors.resolveException(request, response, null, failure);
            return;
        }
        if (staff.isEmpty()) {
            reject(request, response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(staff.get(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + staff.get().role().name()))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) {
        errors.resolveException(request, response, null, new BadCredentialsException("Invalid staff session"));
    }

    private static List<String> accessCookies(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return List.of();
        return Arrays.stream(cookies).filter(cookie -> StaffSessionVerifier.ACCESS_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).toList();
    }
}
