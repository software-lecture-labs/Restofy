package com.restofy.qr.infrastructure.security.menu;

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
 * Verifies the request-bound service credential of the Menu service. It knows no other caller: Order and Waiter have
 * their own filters and chains. With no Menu keys configured every request is rejected (401).
 * <p>
 * Menu does not know the restaurant before asking, so its credential carries no {@code restaurant_id}; a credential that
 * names one is refused. QR answers with the restaurant of the guest session, checked against its own configured
 * restaurant.
 */
final class MenuCallerFilter extends OncePerRequestFilter {
    static final String AUTHORITY = "MENU_QR_GUEST_VALIDATE";
    static final String SCOPE = "qr:menu-guest-validate";
    static final String ISSUER = "restofy-menu";
    static final String SUBJECT = "menu-service";
    static final String VERIFIED = MenuCallerFilter.class.getName() + ".verified";
    private static final String SCHEME = "Service ";
    private static final int MAX_CREDENTIAL_LENGTH = 4096;
    private final UUID restaurantId;
    private final Clock clock;
    private final JwtParser parser;

    MenuCallerFilter(UUID restaurantId, Clock clock, Map<String, RSAPublicKey> keys) {
        this.restaurantId = restaurantId;
        this.clock = clock;
        parser = Jwts.parser().keyLocator(header -> {
            var key = header.get("kid") instanceof String id ? keys.get(id) : null;
            if (key == null) { throw new JwtException("Unknown Menu key ID"); }
            return key;
        }).sig().clear().add(Jwts.SIG.RS256).and().clock(() -> JwtDates.from(clock.instant()))
                .requireIssuer(ISSUER).requireAudience("restofy-qr")
                .requireSubject(SUBJECT).require("token_type", "service").build();
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
        if (!matches(request, claims, body)) {
            reject(response, HttpStatus.FORBIDDEN);
            return;
        }
        serve(request, response, chain, body);
    }

    private Claims authenticate(String header) {
        if (header == null || !header.startsWith(SCHEME) || header.length() > MAX_CREDENTIAL_LENGTH + SCHEME.length()) {
            throw new IllegalArgumentException("Invalid Menu authorization");
        }
        var claims = parser.parseSignedClaims(header.substring(SCHEME.length())).getPayload();
        var issued = claims.getIssuedAt();
        var notBefore = claims.getNotBefore();
        var expiration = claims.getExpiration();
        var now = clock.instant();
        if (issued == null || !issued.equals(notBefore) || expiration == null || issued.toInstant().isAfter(now)
                || !expiration.toInstant().isAfter(now) || !expiration.after(issued)
                || expiration.toInstant().isAfter(issued.toInstant().plusSeconds(30)) || !standardUuid(claims.getId())) {
            throw new IllegalArgumentException("Invalid Menu credential lifetime or identifier");
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
        if (request.getContentLengthLong() > MenuSignedRequest.MAX_BODY_BYTES) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE);
            return Optional.empty();
        }
        byte[] body = request.getInputStream().readNBytes(MenuSignedRequest.MAX_BODY_BYTES + 1);
        if (body.length > MenuSignedRequest.MAX_BODY_BYTES) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE);
            return Optional.empty();
        }
        return Optional.of(body);
    }

    private static boolean matches(HttpServletRequest request, Claims claims, byte[] body) {
        String query = request.getQueryString();
        String target = request.getRequestURI() + (query == null ? "" : "?" + query);
        return SCOPE.equals(claims.get("scope")) && !claims.containsKey("restaurant_id")
                && request.getMethod().equals(claims.get("method")) && target.equals(claims.get("target"))
                && MenuSignedRequest.digest(body).equals(claims.get("body_sha256"));
    }

    private void serve(HttpServletRequest request, HttpServletResponse response, FilterChain chain, byte[] body)
            throws IOException, ServletException {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(restaurantId, null,
                AuthorityUtils.createAuthorityList(AUTHORITY)));
        SecurityContextHolder.setContext(context);
        request.setAttribute(VERIFIED, Boolean.TRUE);
        try {
            chain.doFilter(new MenuSignedRequest(request, body), response);
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
