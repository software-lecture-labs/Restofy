package com.restofy.qr.infrastructure.security.waiter;

import com.restofy.qr.infrastructure.security.JwtDates;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies the request-bound service credential of the Waiter service. It knows no other caller: Order has its own
 * filter and chain. With no Waiter keys configured every request is rejected (401).
 */
final class WaiterCallerFilter extends OncePerRequestFilter {
    static final String VERIFIED = WaiterCallerFilter.class.getName() + ".verified";
    private static final String SCHEME = "Service ";
    private static final int MAX_CREDENTIAL_LENGTH = 4096;
    private final UUID restaurantId;
    private final Clock clock;
    private final JwtParser parser;

    WaiterCallerFilter(UUID restaurantId, Clock clock, Map<String, RSAPublicKey> keys) {
        this.restaurantId = restaurantId;
        this.clock = clock;
        parser = Jwts.parser().keyLocator(header -> {
            var key = header.get("kid") instanceof String id ? keys.get(id) : null;
            if (key == null) { throw new JwtException("Unknown Waiter key ID"); }
            return key;
        }).sig().clear().add(Jwts.SIG.RS256).and().clock(() -> JwtDates.from(clock.instant()))
                .requireIssuer(WaiterAuthorities.ISSUER).requireAudience("restofy-qr")
                .requireSubject(WaiterAuthorities.SUBJECT).require("token_type", "service").build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hasUnexpectedHeaders(request)) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return;
        }
        final Claims claims;
        try {
            claims = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (JwtException | IllegalArgumentException _) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return;
        }
        var boundedBody = readBody(request, response);
        if (boundedBody.isEmpty()) { return; }
        byte[] body = boundedBody.get();
        var authority = WaiterAuthorities.forScope(claims.get("scope"));
        if (authority.isEmpty() || !matches(request, claims, body)) {
            reject(response, HttpStatus.FORBIDDEN);
            return;
        }
        serve(request, response, chain, body, authority.get());
    }

    private Claims authenticate(String header) {
        if (header == null || !header.startsWith(SCHEME) || header.length() > MAX_CREDENTIAL_LENGTH + SCHEME.length()) {
            throw new IllegalArgumentException("Invalid Waiter authorization");
        }
        var claims = parser.parseSignedClaims(header.substring(SCHEME.length())).getPayload();
        var issued = claims.getIssuedAt();
        var notBefore = claims.getNotBefore();
        var expiration = claims.getExpiration();
        var now = clock.instant();
        if (issued == null || !issued.equals(notBefore) || expiration == null || issued.toInstant().isAfter(now)
                || !expiration.toInstant().isAfter(now) || !expiration.after(issued)
                || expiration.toInstant().isAfter(issued.toInstant().plusSeconds(30)) || !standardUuid(claims.getId())) {
            throw new IllegalArgumentException("Invalid Waiter credential lifetime or identifier");
        }
        return claims;
    }

    private static boolean standardUuid(String value) {
        return value != null && UUID.fromString(value).toString().equals(value);
    }

    private static boolean hasUnexpectedHeaders(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.COOKIE) != null || request.getHeader(HttpHeaders.ORIGIN) != null
                || request.getHeader(HttpHeaders.CONTENT_ENCODING) != null
                || Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() != 1;
    }

    private static Optional<byte[]> readBody(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getContentLengthLong() > WaiterSignedRequest.MAX_BODY_BYTES) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE);
            return Optional.empty();
        }
        byte[] body = request.getInputStream().readNBytes(WaiterSignedRequest.MAX_BODY_BYTES + 1);
        if (body.length > WaiterSignedRequest.MAX_BODY_BYTES) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE);
            return Optional.empty();
        }
        return Optional.of(body);
    }

    private boolean matches(HttpServletRequest request, Claims claims, byte[] body) {
        String query = request.getQueryString();
        String target = request.getRequestURI() + (query == null ? "" : "?" + query);
        return restaurantId.toString().equals(claims.get("restaurant_id"))
                && request.getMethod().equals(claims.get("method")) && target.equals(claims.get("target"))
                && WaiterSignedRequest.digest(body).equals(claims.get("body_sha256"))
                && (!"GET".equals(request.getMethod()) || body.length == 0);
    }

    private void serve(HttpServletRequest request, HttpServletResponse response, FilterChain chain, byte[] body,
                       String authority) throws IOException, ServletException {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(restaurantId, null,
                AuthorityUtils.createAuthorityList(authority)));
        SecurityContextHolder.setContext(context);
        request.setAttribute(VERIFIED, Boolean.TRUE);
        try {
            chain.doFilter(new WaiterSignedRequest(request, body), response);
        } finally {
            request.removeAttribute(VERIFIED);
            SecurityContextHolder.clearContext();
        }
    }

    static void reject(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status.value() + ",\"title\":\"" + status.getReasonPhrase() + "\"}");
    }
}
