package com.restofy.auth.infrastructure.security;

import java.io.IOException;
import java.util.List;

import com.restofy.auth.api.exception.AuthProblems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class NotificationRequestFilter extends OncePerRequestFilter {
    private final NotificationServiceTokens tokens;

    public NotificationRequestFilter(NotificationServiceTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            if (request.getHeader("Origin") != null || request.getHeader("Cookie") != null) {
                throw new BadCredentialsException("Browser credentials are not service credentials");
            }
            var headers = request.getHeaders("Authorization");
            String authorization = headers.hasMoreElements() ? headers.nextElement() : null;
            if (headers.hasMoreElements()) {
                throw new BadCredentialsException("Ambiguous service credential");
            }
            tokens.verify(authorization);
            var authentication = UsernamePasswordAuthenticationToken.authenticated("notification-service", null,
                    List.of(new SimpleGrantedAuthority("STAFF_RECIPIENTS_READ")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (BadCredentialsException _) {
            AuthProblems.write(response, HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
