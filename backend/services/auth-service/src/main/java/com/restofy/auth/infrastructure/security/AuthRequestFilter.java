package com.restofy.auth.infrastructure.security;

import java.io.IOException;
import java.util.List;
import java.util.Set;

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
            boolean session = "GET".equals(request.getMethod()) && "/api/v1/auth/session".equals(path);
            boolean activity = "POST".equals(request.getMethod()) && "/api/v1/auth/activity".equals(path);
            boolean mfa = "POST".equals(request.getMethod()) && Set.of("/api/v1/auth/mfa/enroll",
                    "/api/v1/auth/mfa/enroll/confirm", "/api/v1/auth/mfa/verify", "/api/v1/auth/mfa/recover",
                    "/api/v1/auth/mfa/recovery-codes/regenerate").contains(path);
            if (session || activity || mfa) {
                authenticate(request);
            }
            chain.doFilter(request, response);
        } catch (BadCredentialsException _) {
            AuthProblems.write(response, HttpServletResponse.SC_UNAUTHORIZED);
        }
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
