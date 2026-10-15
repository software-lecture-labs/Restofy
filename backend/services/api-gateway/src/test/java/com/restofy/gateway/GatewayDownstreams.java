package com.restofy.gateway;

import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Test-only HTTP peers; production routes are never replaced. */
final class GatewayDownstreams implements AutoCloseable {

    static final String REQUEST_ID = "X-Restofy-Test-Id";
    static final String RESPONSE_STATUS = "X-Mock-Response-Status";

    record Route(String service, String prefix, String child, String environmentKey) { }

    static final List<Route> ROUTES = List.of(
            new Route("auth-service", "/api/v1/auth", "/login", "AUTH_SERVICE_URL"),
            new Route("order-service", "/api/v1/orders", "/123", "ORDER_SERVICE_URL"),
            new Route("kitchen-service", "/api/v1/kitchen", "/tickets/42", "KITCHEN_SERVICE_URL"),
            new Route("waiter-service", "/api/v1/waiters", "/42", "WAITER_SERVICE_URL"),
            new Route("menu-service", "/api/v1/menu", "/categories", "MENU_SERVICE_URL"),
            new Route("qr-service", "/api/v1/qr", "/tables/42", "QR_SERVICE_URL"),
            new Route("payment-service", "/api/v1/payments", "/42", "PAYMENT_SERVICE_URL"),
            new Route("notification-service", "/api/v1/notifications", "/42", "NOTIFICATION_SERVICE_URL"),
            new Route("admin-service", "/api/v1/admin", "/users", "ADMIN_SERVICE_URL"),
            new Route("rating-service", "/api/v1/ratings", "/42", "RATING_SERVICE_URL"),
            new Route("mail-service", "/api/v1/mail", "/messages", "MAIL_SERVICE_URL"),
            new Route("timer-service", "/api/v1/timers", "/42", "TIMER_SERVICE_URL")
    );

    record Received(String service, String method, String path, String query, byte[] body,
                    Map<String, List<String>> headers) {
        String header(String name) {
            var values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    private final Map<String, HttpServer> servers = new LinkedHashMap<>();
    private final Map<String, URI> addresses = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();

    GatewayDownstreams() {
        try {
            for (var route : ROUTES) {
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                servers.put(route.service(), server);
                addresses.put(route.service(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
                server.createContext("/", exchange -> {
                    try (exchange) {
                        var headers = new LinkedHashMap<String, List<String>>();
                        exchange.getRequestHeaders().forEach((name, values) ->
                                headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
                        received.add(new Received(route.service(), exchange.getRequestMethod(),
                                exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(),
                                exchange.getRequestBody().readAllBytes(), Map.copyOf(headers)));
                        String requestedStatus = exchange.getRequestHeaders().getFirst(RESPONSE_STATUS);
                        int status = requestedStatus == null ? 200 : Integer.parseInt(requestedStatus);
                        byte[] response = responseBody(route.service()).getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
                        exchange.getResponseHeaders().set("X-Downstream-Service", route.service());
                        exchange.sendResponseHeaders(status, response.length);
                        exchange.getResponseBody().write(response);
                    }
                });
                server.start();
            }
        } catch (IOException | RuntimeException error) {
            close();
            if (error instanceof IOException io) throw new UncheckedIOException(io);
            throw (RuntimeException) error;
        }
    }

    static String responseBody(String service) {
        return "mock:" + service + ":çay";
    }

    URI address(String service) {
        return addresses.get(service);
    }

    void register(DynamicPropertyRegistry registry) {
        ROUTES.forEach(route -> registry.add(route.environmentKey(), () -> address(route.service()).toString()));
    }

    List<Received> requests(String id) {
        return received.stream().filter(request -> Objects.equals(id, request.header(REQUEST_ID))).toList();
    }

    void stop(String service) {
        var server = servers.remove(service);
        if (server != null) server.stop(0);
    }

    @Override
    public void close() {
        new ArrayList<>(servers.keySet()).forEach(this::stop);
    }
}
