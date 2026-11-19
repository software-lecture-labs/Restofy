package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class StaffCookieAuthenticationFilter extends OncePerRequestFilter {
    static final String CHALLENGE = "StaffSession realm=\"order\"";
    private final AuthContextPort authContext;
    private final ApiErrorWriter errors;
    private final RequestMatcher staffRoutes;

    StaffCookieAuthenticationFilter(AuthContextPort authContext, ApiErrorWriter errors, RequestMatcher staffRoutes) {
        this.authContext = authContext;
        this.errors = errors;
        this.staffRoutes = staffRoutes;
    }

    static Set<String> allowedOrigins(String configuredOrigins) {
        Set<String> origins = Arrays.stream(configuredOrigins.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (origins.isEmpty() || !origins.stream().allMatch(StaffCookieAuthenticationFilter::validOrigin)) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS must contain explicit HTTP origins for order-auth");
        }
        return origins;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !staffRoutes.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var credentials = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (credentials.hasMoreElements()) {
            String credential = credentials.nextElement();
            if (credentials.hasMoreElements() || credential.indexOf(',') >= 0) {
                errors.write(response, ErrorCode.INVALID_REQUEST, "Only one Authorization credential is allowed");
                return;
            }
            if (StaffAuthenticationFilter.isStaffScheme(credential)) {
                reject(response);
                return;
            }
            // Explicit guest or service credentials are handled by their own filters.
            chain.doFilter(request, response);
            return;
        }
        List<String> accessCookies = cookies(request, StaffCsrfProtection.ACCESS_COOKIE);
        if (accessCookies.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (accessCookies.size() != 1) {
            reject(response);
            return;
        }
        Optional<StaffActor> actor;
        try {
            actor = authContext.resolveStaff(accessCookies.getFirst());
        } catch (RuntimeException _) {
            errors.write(response, ErrorCode.DEPENDENCY_UNAVAILABLE, "Staff authentication is currently unavailable");
            return;
        }
        if (actor.isEmpty()) {
            reject(response);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new StaffAuthentication(actor.get()));
        chain.doFilter(request, response);
    }

    private static List<String> cookies(HttpServletRequest request, String name) {
        var cookies = request.getCookies();
        return cookies == null ? List.of() : Arrays.stream(cookies).filter(cookie -> name.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue).toList();
    }

    private static boolean validOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && "".equals(uri.getRawPath()) && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535));
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        errors.unauthenticated(response, "A valid staff session is required", CHALLENGE);
    }
}
