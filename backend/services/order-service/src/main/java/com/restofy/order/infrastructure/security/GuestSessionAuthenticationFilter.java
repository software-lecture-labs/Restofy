package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Authenticates the guest cookie against QR and verifies browser proof on guest mutations.
 */
public class GuestSessionAuthenticationFilter extends OncePerRequestFilter {

    private static final String SESSION_COOKIE = "restofy_guest";
    private static final String CSRF_COOKIE = "restofy_guest_csrf";
    private static final String CSRF_HEADER = "X-Guest-CSRF";
    private static final Pattern CREDENTIAL = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern ORDER_PATH = Pattern.compile("/api/v1/orders(?:/[^/]+(?:/(?:history|cancellations))?)?");
    private static final Pattern CANCEL_PATH = Pattern.compile("/api/v1/orders/[^/]+/cancellations");

    private final QrSessionPort qrSessions;
    private final ApiErrorWriter errors;
    private final Set<String> origins;

    GuestSessionAuthenticationFilter(QrSessionPort qrSessions, ApiErrorWriter errors, String allowedOrigins) {
        this.qrSessions = qrSessions;
        this.errors = errors;
        this.origins = allowedOrigins.isBlank() ? Set.of() : Arrays.stream(allowedOrigins.split(",", -1)).map(String::trim)
                .map(GuestSessionAuthenticationFilter::validateOrigin).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return !guestRoute(request)
                || StaffAuthenticationFilter.isStaffScheme(authorization)
                || ServiceAuthenticationFilter.isServiceScheme(authorization);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            reject(response);
            return;
        }
        var tokens = cookies(request, SESSION_COOKIE);
        if (tokens.length == 0) {
            chain.doFilter(request, response);
            return;
        }
        if (tokens.length != 1 || !validCredential(tokens[0].getValue())) {
            reject(response);
            return;
        }
        if (!safeMethod(request.getMethod()) && !validBrowserProof(request)) {
            errors.write(response, ErrorCode.ACCESS_DENIED, "Guest CSRF proof or Origin is invalid");
            return;
        }
        try {
            var context = qrSessions.validateGuestSession(tokens[0].getValue());
            if (context.isEmpty() || !context.get().isActive()) {
                reject(response);
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new GuestAuthentication(context.get()));
        } catch (BusinessException unavailable) {
            errors.write(response, unavailable.code(), unavailable.getMessage());
            return;
        } catch (RuntimeException _) {
            errors.write(response, ErrorCode.DEPENDENCY_UNAVAILABLE, "Session validation is currently unavailable");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean guestRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/carts/")) {
            return true;
        }
        return switch (request.getMethod()) {
            case "GET", "HEAD" -> ORDER_PATH.matcher(path).matches();
            case "POST" -> "/api/v1/orders".equals(path) || CANCEL_PATH.matcher(path).matches();
            default -> false;
        };
    }

    private static boolean safeMethod(String method) {
        return switch (method) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    private boolean validBrowserProof(HttpServletRequest request) {
        var origin = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        var proof = Collections.list(request.getHeaders(CSRF_HEADER));
        var cookies = cookies(request, CSRF_COOKIE);
        return origin.size() == 1 && origins.contains(origin.getFirst()) && proof.size() == 1
                && cookies.length == 1 && validCredential(cookies[0].getValue()) && validCredential(proof.getFirst())
                && MessageDigest.isEqual(cookies[0].getValue().getBytes(StandardCharsets.US_ASCII),
                        proof.getFirst().getBytes(StandardCharsets.US_ASCII));
    }

    private static Cookie[] cookies(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        return cookies == null ? new Cookie[0] : Arrays.stream(cookies).filter(cookie -> name.equals(cookie.getName()))
                .toArray(Cookie[]::new);
    }

    private static boolean validCredential(String value) {
        return value != null && CREDENTIAL.matcher(value).matches();
    }

    private static String validateOrigin(String value) {
        try {
            URI origin = URI.create(value);
            if (("https".equals(origin.getScheme()) || "http".equals(origin.getScheme())) && origin.getHost() != null
                    && origin.getRawUserInfo() == null && origin.getRawPath().isEmpty()
                    && origin.getRawQuery() == null && origin.getRawFragment() == null) {
                return value;
            }
        } catch (IllegalArgumentException _) {
            // Report a safe configuration error below.
        }
        throw new IllegalStateException("CORS_ALLOWED_ORIGINS must contain exact HTTP or HTTPS origins");
    }

    private void reject(HttpServletResponse response) throws IOException {
        errors.unauthenticated(response, "A valid guest session is required", ApiErrorWriter.GUEST_SCHEME);
    }
}
