package com.restofy.menu.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A contract stand-in for QR's Menu guest check, on a loopback port. It is NOT the QR service: it re-implements, from the
 * QR source (PR #65, {@code MenuCallerFilter} and {@code MenuGuestSessionController}) and its guide, what QR demands of the
 * caller and how it answers, so that Menu's real HTTP client can be tested against that contract. What it proves is how
 * Menu behaves; it does not prove how QR decides. Tests that use it must never be reported as end-to-end tests.
 *
 * <p>Caller checks (any failure is 401 for the credential, 403 for the binding, like QR): exactly one {@code Authorization:
 * Service <jwt>}, no {@code Cookie}, {@code Origin} or {@code Content-Encoding}; RS256 with a known {@code kid};
 * {@code iss=restofy-menu}, {@code sub=menu-service}, {@code aud=restofy-qr}, {@code token_type=service}; {@code iat==nbf},
 * a lifetime of at most 30 seconds that has not started in the future and has not ended; a canonical UUID {@code jti};
 * {@code scope=qr:menu-guest-validate}; no {@code restaurant_id}; method, target and SHA-256 of the body bound; body at most
 * 1 KiB.
 */
public final class StubQrServer implements AutoCloseable {

    public static final String PATH = "/api/v1/internal/qr/menu/guest-sessions/validate";
    public static final String KEY_ID = "menu-test";
    private static final Pattern TOKEN_BODY = Pattern.compile("\\{\"guestToken\":\"([A-Za-z0-9_-]{43})\"}");

    /** What QR answers for one guest token. */
    public sealed interface Answer {
        record Active(UUID restaurantId) implements Answer { }
        record Gone() implements Answer { }
        record Raw(int status, String contentType, String body) implements Answer { }
        record Slow(long millis) implements Answer { }
    }

    /** One request as QR saw it. */
    public record Received(String method, String path, Map<String, List<String>> headers, String body, int status) { }

    private final HttpServer server;
    private final KeyPair keys;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public StubQrServer(KeyPair menuKeys) throws IOException {
        this.keys = menuKeys;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    public String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void answer(String guestToken, Answer answer) {
        answers.put(guestToken, answer);
    }

    public void reset() {
        answers.clear();
        received.clear();
    }

    public List<Received> received() {
        return new ArrayList<>(received);
    }

    public Received only() {
        List<Received> all = received();
        if (all.size() != 1) {
            throw new AssertionError("expected exactly one request but QR saw " + all.size());
        }
        return all.get(0);
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(1025);
        Map<String, List<String>> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), List.copyOf(values)));
        Reply reply;
        try {
            reply = serve(exchange, headers, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply = new Reply(500, null, "", Map.of());
        }
        // Recorded BEFORE the answer is sent: the client can have the answer (and a test can read the record) only after this
        // line, so a test never races the server thread.
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()),
                Collections.unmodifiableMap(headers), new String(body, StandardCharsets.UTF_8), reply.status()));
        send(exchange, reply);
        exchange.close();
    }

    /** What the stand-in will answer, decided before anything is sent. */
    private record Reply(int status, String contentType, String body, Map<String, String> headers) { }

    private Reply serve(HttpExchange exchange, Map<String, List<String>> headers, byte[] body) throws IOException, InterruptedException {
        if (!"POST".equals(exchange.getRequestMethod()) || !PATH.equals(exchange.getRequestURI().getRawPath())) {
            return reply(exchange, 403, "application/problem+json", "{\"status\":403,\"title\":\"Forbidden\"}");
        }
        if (headers.containsKey("cookie") || headers.containsKey("origin") || headers.containsKey("content-encoding")
                || headers.getOrDefault("authorization", List.of()).size() != 1) {
            return reply(exchange, 401, "application/problem+json", "{\"status\":401,\"title\":\"Unauthorized\"}");
        }
        Claims claims;
        try {
            claims = authenticate(headers.get("authorization").get(0));
        } catch (JwtException | IllegalArgumentException e) {
            return reply(exchange, 401, "application/problem+json", "{\"status\":401,\"title\":\"Unauthorized\"}");
        }
        if (body.length > 1024 || !bound(exchange, claims, body)) {
            return reply(exchange, body.length > 1024 ? 413 : 403, "application/problem+json", "{\"status\":403,\"title\":\"Forbidden\"}");
        }
        Matcher token = TOKEN_BODY.matcher(new String(body, StandardCharsets.UTF_8));
        if (!token.matches()) {
            return reply(exchange, 400, "application/problem+json", "{\"status\":400,\"title\":\"Bad Request\"}");
        }
        Answer answer = answers.get(token.group(1));
        if (answer == null || answer instanceof Answer.Gone) {
            return reply(exchange, 404, "application/problem+json", "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
                    + "\"detail\":\"Guest session is unavailable.\",\"code\":\"GUEST_SESSION_UNAVAILABLE\"}");
        }
        if (answer instanceof Answer.Slow slow) {
            Thread.sleep(slow.millis());
            return reply(exchange, 200, "application/json", "{\"restaurantId\":\"" + UUID.randomUUID() + "\",\"state\":\"ACTIVE\"}");
        }
        if (answer instanceof Answer.Raw raw) {
            Map<String, String> extra = raw.status() == 302 ? Map.of("Location", origin() + "/elsewhere") : Map.of();
            return new Reply(raw.status(), raw.contentType(), raw.body(), extra);
        }
        UUID restaurant = ((Answer.Active) answer).restaurantId();
        return new Reply(200, "application/json", "{\"restaurantId\":\"" + restaurant + "\",\"state\":\"ACTIVE\"}",
                Map.of("Cache-Control", "no-store"));
    }

    private Claims authenticate(String header) {
        if (header == null || !header.startsWith("Service ")) {
            throw new IllegalArgumentException("scheme");
        }
        Jws<Claims> jws = Jwts.parser().keyLocator(h -> "menu-test".equals(h.get("kid")) ? (RSAPublicKey) keys.getPublic() : null)
                .sig().clear().add(Jwts.SIG.RS256).and().requireIssuer("restofy-menu").requireAudience("restofy-qr")
                .requireSubject("menu-service").require("token_type", "service").build().parseSignedClaims(header.substring(8));
        Claims claims = jws.getPayload();
        var issued = claims.getIssuedAt();
        var notBefore = claims.getNotBefore();
        var expiration = claims.getExpiration();
        Instant now = Instant.now();
        if (issued == null || !issued.equals(notBefore) || expiration == null || issued.toInstant().isAfter(now)
                || !expiration.toInstant().isAfter(now) || !expiration.after(issued)
                || expiration.toInstant().isAfter(issued.toInstant().plusSeconds(30)) || !standardUuid(claims.getId())) {
            throw new IllegalArgumentException("lifetime");
        }
        return claims;
    }

    private static boolean standardUuid(String value) {
        return value != null && UUID.fromString(value).toString().equals(value);
    }

    private static boolean bound(HttpExchange exchange, Claims claims, byte[] body) {
        String query = exchange.getRequestURI().getRawQuery();
        String target = exchange.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
        try {
            return "qr:menu-guest-validate".equals(claims.get("scope")) && !claims.containsKey("restaurant_id")
                    && exchange.getRequestMethod().equals(claims.get("method")) && target.equals(claims.get("target"))
                    && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)).equals(claims.get("body_sha256"));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Reply reply(HttpExchange exchange, int status, String contentType, String body) {
        return new Reply(status, contentType, body, Map.of());
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        if (reply.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        }
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }
}
