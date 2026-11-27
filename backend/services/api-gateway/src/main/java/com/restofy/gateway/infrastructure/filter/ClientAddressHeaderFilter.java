package com.restofy.gateway.infrastructure.filter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Drops what the client claims about its own address.
 *
 * <p>The Gateway is the edge: {@code X-Forwarded-For}, {@code Forwarded} and {@code X-Real-IP} sent by a client are
 * claims, not facts. They are removed before routing, and Spring Cloud Gateway then adds the address that really
 * connected (see {@code trusted-proxies} in application.properties). Services such as Auth read that last entry.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientAddressHeaderFilter extends OncePerRequestFilter {
    private static final Set<String> CLIENT_CONTROLLED = Set.of("x-forwarded-for", "forwarded", "x-real-ip");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new WithoutClientAddressClaims(request), response);
    }

    private static boolean isClaim(String name) {
        return name != null && CLIENT_CONTROLLED.contains(name.toLowerCase(Locale.ROOT));
    }

    private static final class WithoutClientAddressClaims extends HttpServletRequestWrapper {
        WithoutClientAddressClaims(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return isClaim(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isClaim(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            for (String name : Collections.list(super.getHeaderNames())) {
                if (!isClaim(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }
    }
}
