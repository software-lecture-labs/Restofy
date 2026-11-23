package com.restofy.order.infrastructure.security;

import com.restofy.order.domain.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Rejects multiple credentials before the authentication filters choose a scheme. */
final class AuthorizationHeaderFilter extends OncePerRequestFilter {

    private final ApiErrorWriter errors;

    AuthorizationHeaderFilter(ApiErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (values.hasMoreElements()) {
            String value = values.nextElement();
            if (values.hasMoreElements() || value.indexOf(',') >= 0) {
                errors.write(response, ErrorCode.INVALID_REQUEST, "Only one Authorization credential is allowed");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
