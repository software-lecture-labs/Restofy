package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.TrustedOrderCaller;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies the request-bound service credential of the Order service on the catalog lookup (ORDER-REQUEST-01; Order's side is
 * PR #79). It knows no other caller and nothing a browser sends: a request with a {@code Cookie}, {@code Origin} or
 * {@code Content-Encoding} header is refused, and the staff and guest identities of Menu never reach this chain.
 *
 * <p>Checks, in this order (401 for the credential, 403 for what it is bound to):
 * <ol>
 *   <li>Exactly one {@code Authorization: Service <jwt>}; RS256 only; the key is chosen by {@code kid} from the configured Order
 *       keys (none configured: everything is 401).</li>
 *   <li>{@code iss=restofy-order}, {@code sub=order-service}, {@code aud=restofy-menu} (the ONLY audience: a credential also meant for another service is refused), {@code token_type=service}; {@code iat==nbf},
 *       a lifetime of at most 30 seconds from {@code iat}, not valid in the future and not expired (a 5 second clock skew is
 *       allowed either way, because Order does not back-date {@code iat}); a canonical UUID {@code jti}.</li>
 *   <li>{@code scope=menu:catalog-read}; a canonical {@code restaurant_id}; {@code method}, {@code target} and the SHA-256 of the
 *       exact body bound to this request; the route is exactly {@code POST} on the lookup path (anything else under
 *       the internal prefix is 403).</li>
 *   <li><b>The key must be authorized for that restaurant</b> ({@link OrderCallerSettings}): a key with no restaurant entry reads no
 *       restaurant at all, so it is 403 for every request.</li>
 * </ol>
 * The restaurant in the signed credential must also equal the one in the body; that is checked where the body is read
 * ({@code OrderCatalogService}). A used {@code jti} is not stored: the lookup is read-only and returns the same data to the same
 * authorized caller, so a replay within 30 seconds gives an attacker nothing; a state-changing route here would need a command id.
 */
final class OrderCallerFilter extends OncePerRequestFilter {

    static final String LOOKUP_PATH = "/api/v1/internal/menu/catalog/products:lookup";
    static final String AUTHORITY = "ORDER_CATALOG_READ";
    static final String SCOPE = "menu:catalog-read";
    static final String ISSUER = "restofy-order";
    static final String SUBJECT = "order-service";
    static final String AUDIENCE = "restofy-menu";
    static final long MAX_LIFETIME_SECONDS = 30;
    static final long SKEW_SECONDS = 5;
    private static final String SCHEME = "Service ";
    private static final int MAX_CREDENTIAL_LENGTH = 4096;

    private final OrderCallerSettings settings;
    private final Clock clock;

    OrderCallerFilter(OrderCallerSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** The key id and claims of a credential that passed every cryptographic and lifetime check. */
    private record Verified(String keyId, Claims claims) { }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hasUnexpectedHeaders(request)) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return;
        }
        final Verified verified;
        try {
            verified = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (JwtException | IllegalArgumentException _) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return;
        }
        if (request.getContentLengthLong() > OrderSignedRequest.MAX_BODY_BYTES) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(OrderSignedRequest.MAX_BODY_BYTES + 1);
        if (body.length > OrderSignedRequest.MAX_BODY_BYTES) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE);
            return;
        }
        Optional<UUID> restaurant = boundRestaurant(request, verified.claims(), body);
        if (restaurant.isEmpty() || !settings.mayRead(verified.keyId(), restaurant.get())) {
            reject(response, HttpStatus.FORBIDDEN);
            return;
        }
        serve(request, response, chain, body, new TrustedOrderCaller(restaurant.get()), verified.keyId());
    }

    private Verified authenticate(String header) {
        if (header == null || !header.startsWith(SCHEME) || header.length() > MAX_CREDENTIAL_LENGTH + SCHEME.length()) {
            throw new IllegalArgumentException("Invalid Order authorization");
        }
        var jws = Jwts.parser().keyLocator(h -> settings.key(h.get("kid") instanceof String id ? id : null))
                .sig().clear().add(Jwts.SIG.RS256).and()
                .clock(() -> Date.from(clock.instant())).clockSkewSeconds(SKEW_SECONDS)
                .requireIssuer(ISSUER).requireSubject(SUBJECT).requireAudience(AUDIENCE).require("token_type", "service")
                .build().parseSignedClaims(header.substring(SCHEME.length()));
        Claims claims = jws.getPayload();
        Date issued = claims.getIssuedAt();
        Date notBefore = claims.getNotBefore();
        Date expiration = claims.getExpiration();
        Instant now = clock.instant();
        if (issued == null || !issued.equals(notBefore) || expiration == null
                || issued.toInstant().isAfter(now.plusSeconds(SKEW_SECONDS))
                || expiration.toInstant().isBefore(now.minusSeconds(SKEW_SECONDS)) || !expiration.after(issued)
                || expiration.toInstant().isAfter(issued.toInstant().plusSeconds(MAX_LIFETIME_SECONDS))
                || !standardUuid(claims.getId()) || !java.util.Set.of(AUDIENCE).equals(claims.getAudience())) {
            throw new IllegalArgumentException("Invalid Order credential lifetime or identifier");
        }
        return new Verified(jws.getHeader().getKeyId(), claims);
    }

    private static boolean standardUuid(String value) {
        return value != null && UUID.fromString(value).toString().equals(value);
    }

    private static boolean hasUnexpectedHeaders(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.COOKIE) != null || request.getHeader(HttpHeaders.ORIGIN) != null
                || request.getHeader(HttpHeaders.CONTENT_ENCODING) != null
                || Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() != 1;
    }

    /** The restaurant of the credential when scope, route, target and body are all bound to THIS request, otherwise empty. */
    private static Optional<UUID> boundRestaurant(HttpServletRequest request, Claims claims, byte[] body) {
        String query = request.getQueryString();
        String target = request.getRequestURI() + (query == null ? "" : "?" + query);
        if (!SCOPE.equals(claims.get("scope")) || !"POST".equals(request.getMethod()) || !LOOKUP_PATH.equals(target)
                || !request.getMethod().equals(claims.get("method")) || !target.equals(claims.get("target"))
                || !digest(body).equals(claims.get("body_sha256")) || !(claims.get("restaurant_id") instanceof String raw)
                || raw.length() != 36) {
            return Optional.empty();
        }
        try {
            UUID restaurant = UUID.fromString(raw);
            return restaurant.toString().equals(raw) ? Optional.of(restaurant) : Optional.empty();
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void serve(HttpServletRequest request, HttpServletResponse response, FilterChain chain, byte[] body,
            TrustedOrderCaller caller, String keyId) throws IOException, ServletException {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(keyId, null,
                AuthorityUtils.createAuthorityList(AUTHORITY)));
        SecurityContextHolder.setContext(context);
        RequestOrderCallerProvider.bind(request, caller);
        try {
            chain.doFilter(new OrderSignedRequest(request, body), response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static void reject(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":" + status.value() + ",\"title\":\"" + status.getReasonPhrase() + "\"}");
    }
}
