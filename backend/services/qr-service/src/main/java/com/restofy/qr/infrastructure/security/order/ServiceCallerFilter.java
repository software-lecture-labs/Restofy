package com.restofy.qr.infrastructure.security.order;

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
import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies the request-bound service credential of an internal caller (Order, Waiter). A key ID belongs to exactly
 * one caller, so a token signed with one caller's key can never carry another caller's issuer, subject or scope.
 */
final class ServiceCallerFilter extends OncePerRequestFilter {
    static final String VERIFIED = ServiceCallerFilter.class.getName() + ".verified";
    private static final String SCHEME = "Service ";
    private static final int MAX_CREDENTIAL_LENGTH = 4096;
    private final UUID restaurantId;
    private final Clock clock;
    private final Map<String, ServiceCaller> callersByKeyId;
    private final JwtParser parser;

    ServiceCallerFilter(UUID restaurantId, Clock clock, List<ServiceCaller> callers) {
        this.restaurantId = restaurantId;
        this.clock = clock;
        this.callersByKeyId = byKeyId(callers);
        parser = Jwts.parser().keyLocator(header -> {
            var caller = header.get("kid") instanceof String id ? callersByKeyId.get(id) : null;
            if (caller == null) { throw new JwtException("Unknown service key ID"); }
            return caller.keys().get((String) header.get("kid"));
        }).sig().clear().add(Jwts.SIG.RS256).and().clock(() -> JwtDates.from(clock.instant()))
                .requireAudience("restofy-qr").require("token_type", "service").build();
    }

    private static Map<String, ServiceCaller> byKeyId(List<ServiceCaller> callers) {
        Map<String, ServiceCaller> result = new HashMap<>();
        for (var caller : callers) {
            for (String keyId : caller.keys().keySet()) {
                if (result.put(keyId, caller) != null) {
                    throw new IllegalStateException("A service key ID must belong to exactly one caller");
                }
            }
        }
        return Map.copyOf(result);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hasUnexpectedHeaders(request)) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return;
        }
        final Authenticated authenticated;
        try {
            authenticated = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (JwtException | IllegalArgumentException _) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return;
        }
        var boundedBody = readBody(request, response);
        if (boundedBody.isEmpty()) { return; }
        byte[] body = boundedBody.get();
        if (!matches(request, authenticated, body)) {
            reject(response, HttpStatus.FORBIDDEN);
            return;
        }
        serve(request, response, chain, body, authenticated.caller());
    }

    private Authenticated authenticate(String header) {
        if (header == null || !header.startsWith(SCHEME) || header.length() > MAX_CREDENTIAL_LENGTH + SCHEME.length()) {
            throw new IllegalArgumentException("Invalid service authorization");
        }
        var jws = parser.parseSignedClaims(header.substring(SCHEME.length()));
        var claims = jws.getPayload();
        var caller = callersByKeyId.get(jws.getHeader().getKeyId());
        if (caller == null || !caller.issuer().equals(claims.getIssuer()) || !caller.subject().equals(claims.getSubject())) {
            throw new JwtException("Credential identity does not belong to the signing key");
        }
        var issued = claims.getIssuedAt();
        var notBefore = claims.getNotBefore();
        var expiration = claims.getExpiration();
        var now = clock.instant();
        if (issued == null || !issued.equals(notBefore) || expiration == null || issued.toInstant().isAfter(now)
                || !expiration.toInstant().isAfter(now) || !expiration.after(issued)
                || expiration.toInstant().isAfter(issued.toInstant().plusSeconds(30)) || !standardUuid(claims.getId())) {
            throw new IllegalArgumentException("Invalid service credential lifetime or identifier");
        }
        return new Authenticated(caller, claims);
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
        if (request.getContentLengthLong() > OrderSignedRequest.MAX_BODY_BYTES) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE);
            return Optional.empty();
        }
        byte[] body = request.getInputStream().readNBytes(OrderSignedRequest.MAX_BODY_BYTES + 1);
        if (body.length > OrderSignedRequest.MAX_BODY_BYTES) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE);
            return Optional.empty();
        }
        return Optional.of(body);
    }

    private boolean matches(HttpServletRequest request, Authenticated authenticated, byte[] body) {
        var claims = authenticated.claims();
        String query = request.getQueryString();
        String target = request.getRequestURI() + (query == null ? "" : "?" + query);
        return authenticated.caller().scope().equals(claims.get("scope")) && restaurantId.toString().equals(claims.get("restaurant_id"))
                && request.getMethod().equals(claims.get("method")) && target.equals(claims.get("target"))
                && OrderSignedRequest.digest(body).equals(claims.get("body_sha256"))
                && (!"GET".equals(request.getMethod()) || body.length == 0);
    }

    private void serve(HttpServletRequest request, HttpServletResponse response, FilterChain chain, byte[] body,
                       ServiceCaller caller) throws IOException, ServletException {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(restaurantId, null,
                AuthorityUtils.createAuthorityList(caller.authority())));
        SecurityContextHolder.setContext(context);
        request.setAttribute(VERIFIED, Boolean.TRUE);
        try {
            chain.doFilter(new OrderSignedRequest(request, body), response);
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

    private record Authenticated(ServiceCaller caller, Claims claims) { }
}
