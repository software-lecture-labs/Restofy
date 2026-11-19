package com.restofy.qr.infrastructure.security.guest;

import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Browser proof for the exact guest routes (ADR-QR-002 K6): exact Origin allowlist and, for cookie-authenticated
 * writes, the double-submit CSRF proof. Guest and staff contexts never mix: staff cookies are ignored here.
 */
public final class GuestRequestFilter extends OncePerRequestFilter {
    private static final String PREFIX = "/api/v1/qr";
    private static final String ACTIVITY = PREFIX + "/guest-sessions/activity";
    private final Q5SecuritySettings settings;

    public GuestRequestFilter(Q5SecuritySettings settings) {
        this.settings = settings;
    }

    public static boolean guestPath(String method, String path) {
        return "POST".equals(method) && (path.equals(PREFIX + "/validate")
                || path.equals(PREFIX + "/table-sessions/open") || path.equals(ACTIVITY));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!guestPath(request.getMethod(), request.getRequestURI())) { chain.doFilter(request, response); return; }
        boolean activity = request.getRequestURI().equals(ACTIVITY);
        try {
            origin(request);
            // The guest credential is only a cookie; a Bearer header on these routes is never a supported transport.
            if (request.getHeader("Authorization") != null) { throw new IllegalArgumentException(); }
            if (!activity) { json(request); }
        } catch (RuntimeException _) { problem(response, 403); return; }
        if (activity) {
            try { token(request); }
            catch (InvalidGuestSessionException _) { problem(response, 401); return; }
            try { csrf(request); } catch (RuntimeException _) { problem(response, 403); return; }
        }
        chain.doFilter(request, response);
    }

    private void origin(HttpServletRequest request) {
        var origins = Collections.list(request.getHeaders("Origin"));
        if (origins.size() != 1 || !settings.origins().contains(origins.getFirst())) { throw new IllegalArgumentException(); }
    }

    private static void json(HttpServletRequest request) {
        if (!MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
            throw new IllegalArgumentException();
        }
    }

    private static void token(HttpServletRequest request) {
        String value = GuestCookies.single(request, GuestCookies.TOKEN);
        if (value == null || !value.matches(GuestCookies.PATTERN)) { throw new InvalidGuestSessionException(); }
    }

    private static void csrf(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders(GuestCookies.CSRF_HEADER));
        String cookie = GuestCookies.single(request, GuestCookies.CSRF);
        if (headers.size() != 1 || cookie == null || !cookie.matches(GuestCookies.PATTERN)
                || !MessageDigest.isEqual(cookie.getBytes(StandardCharsets.US_ASCII),
                        headers.getFirst().getBytes(StandardCharsets.US_ASCII))) { throw new IllegalArgumentException(); }
    }

    private static void problem(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status + ",\"title\":\"Guest request rejected\"}");
    }
}
