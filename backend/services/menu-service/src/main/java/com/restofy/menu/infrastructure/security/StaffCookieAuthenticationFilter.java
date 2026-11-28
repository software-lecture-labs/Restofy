package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.AuthenticatedActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates a management request from Auth's HttpOnly {@code restofy_access} cookie, the only credential source
 * of the {@code staff-v1} contract. {@link StaffJwtVerifier} proves the cookie; the verified claims, never the request,
 * decide who the staff member, the restaurant and the role are. Headers such as {@code X-Restaurant-Id}, {@code X-Role}
 * or {@code X-User-Id}, an {@code Authorization} header (Basic or Bearer) and request parameters are not read.
 *
 * <p>No cookie leaves the request anonymous (a 401 later, from the chain). A repeated, malformed, tampered, expired,
 * wrong-audience or unverifiable cookie is a 401 right here, and so is any cookie while no verification key is
 * configured. The request is never given a partial identity.
 *
 * <p>Created by the security configuration and not a Spring bean, otherwise Boot would also register it as a global
 * servlet filter.
 */
final class StaffCookieAuthenticationFilter extends OncePerRequestFilter {

    static final String ACCESS_COOKIE = "restofy_access";

    private final StaffJwtVerifier verifier;

    StaffCookieAuthenticationFilter(StaffJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> credentials = accessCookies(request);
        if (credentials.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AuthenticatedActor> actor = credentials.size() == 1 ? verifier.verify(credentials.getFirst())
                : Optional.empty();
        if (actor.isEmpty()) {
            SecurityContextHolder.clearContext();
            SecurityProblems.unauthenticated(response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor.get(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.get().role().name()))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static List<String> accessCookies(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        return Arrays.stream(cookies).filter(cookie -> ACCESS_COOKIE.equals(cookie.getName())).map(Cookie::getValue).toList();
    }
}
