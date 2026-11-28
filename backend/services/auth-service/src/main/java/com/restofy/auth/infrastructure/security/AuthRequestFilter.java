package com.restofy.auth.infrastructure.security;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.restofy.auth.api.exception.AuthProblems;
import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.infrastructure.config.AuthSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthRequestFilter extends OncePerRequestFilter {
    private static final String STAFF_DIRECTORY = "/api/v1/auth/staff";
    /** Mirrors the {@code /api/v1/auth/staff/*}{@code /role|deactivate} matchers: exactly one path segment. */
    private static final Pattern STAFF_CHANGE = Pattern.compile("/api/v1/auth/staff/[^/]+/(?:role|deactivate)");
    private final AuthSettings settings;
    private final StaffJwtTokens jwt;
    private final StaffSessionService sessions;

    public AuthRequestFilter(AuthSettings settings, StaffJwtTokens jwt, StaffSessionService sessions) {
        this.settings = settings;
        this.jwt = jwt;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            String origin = request.getHeader("Origin");
            boolean safe = Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod());
            if ((!safe || origin != null) && (origin == null || !settings.origins().contains(origin))) {
                AuthProblems.write(response, HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (requiresSession(request.getMethod(), path)) {
                authenticate(request);
            }
            chain.doFilter(request, response);
        } catch (BadCredentialsException _) {
            AuthProblems.write(response, HttpServletResponse.SC_UNAUTHORIZED);
        } catch (org.springframework.web.server.ResponseStatusException unavailable) {
            if (unavailable.getStatusCode().value()!=503) throw unavailable;
            AuthProblems.write(response,HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
    }

    private static boolean requiresSession(String method, String path) {
        boolean session = "GET".equals(method) && "/api/v1/auth/session".equals(path);
        boolean activity = "POST".equals(method) && "/api/v1/auth/activity".equals(path);
        boolean mfa = "POST".equals(method) && Set.of("/api/v1/auth/mfa/enroll",
                "/api/v1/auth/mfa/enroll/confirm", "/api/v1/auth/mfa/verify", "/api/v1/auth/mfa/recover",
                "/api/v1/auth/mfa/recovery-codes/regenerate").contains(path);
        boolean staff = (("GET".equals(method) || "POST".equals(method)) && STAFF_DIRECTORY.equals(path))
                || ("POST".equals(method) && STAFF_CHANGE.matcher(path).matches());
        return session || activity || mfa || staff;
    }

    private void authenticate(HttpServletRequest request) {
        String access = AuthCookies.read(request, AuthCookies.ACCESS);
        String pending = AuthCookies.read(request, AuthCookies.MFA);
        if (access != null && pending != null) {
            throw new BadCredentialsException("Ambiguous session");
        }
        var identity = jwt.verify(access == null ? pending : access, access == null);
        sessions.authenticate(identity);
        var authorities = access == null ? List.of(new SimpleGrantedAuthority("MFA_PENDING"))
                : List.of(new SimpleGrantedAuthority("STAFF_SESSION"), new SimpleGrantedAuthority("ROLE_" + identity.role().name()));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(identity, null, authorities));
    }
}
