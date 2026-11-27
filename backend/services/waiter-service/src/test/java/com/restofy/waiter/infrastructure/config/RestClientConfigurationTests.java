package com.restofy.waiter.infrastructure.config;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.infrastructure.client.notification.NotificationClient;
import com.restofy.waiter.infrastructure.client.order.OrderClient;
import com.restofy.waiter.infrastructure.client.qr.QrCleanRequestDto;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.client.qr.QrCloseRequestDto;
import com.restofy.waiter.infrastructure.security.StaffAccessToken;
import com.restofy.waiter.infrastructure.client.qr.WaiterQrRequestSigner;
import com.restofy.waiter.support.DownstreamStub;
import com.restofy.waiter.support.FakeBackends;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The existing environment contract (QR_SERVICE_URL, ORDER_SERVICE_URL, REST_CLIENT_*_TIMEOUT_MS) maps to typed
 * properties through the real application.properties; invalid values stop the service from starting.
 */
class RestClientConfigurationTests {

    private final DownstreamStub peer = DownstreamStub.start();

    @BeforeEach
    void routes() {
        peer.reset();
    }

    @AfterEach
    void stopPeer() {
        peer.close();
    }

    private Map<String, Object> environment() {
        Map<String, Object> env = new HashMap<>();
        env.put("QR_SERVICE_URL", peer.baseUrl().toString());
        env.put("ORDER_SERVICE_URL", peer.baseUrl().toString());
        env.put("NOTIFICATION_SERVICE_URL", peer.baseUrl().toString());
        env.put("REST_CLIENT_CONNECT_TIMEOUT_MS", "2000");
        env.put("REST_CLIENT_READ_TIMEOUT_MS", "3000");
        return env;
    }

    private ApplicationContextRunner runner(Map<String, Object> env) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, RestClientAutoConfiguration.class))
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withUserConfiguration(RestClientConfiguration.class, QrClient.class, WaiterQrRequestSigner.class,
                        OrderClient.class, NotificationClient.class)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new SystemEnvironmentPropertySource("test-environment", env));
                    try {
                        sources.addLast(new ResourcePropertySource("classpath:application.properties"));
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
    }

    @Test
    void environmentVariablesMapToTypedPropertiesAndTheHttpClient() {
        runner(environment()).run(context -> {
            assertNull(context.getStartupFailure());
            var urls = context.getBean(ServiceUrlProperties.class);
            assertEquals(peer.baseUrl(), urls.qrUrl());
            assertEquals(peer.baseUrl(), urls.orderUrl());
            assertEquals(peer.baseUrl(), urls.notificationUrl());
            assertEquals(new RestClientProperties(2000, 3000), context.getBean(RestClientProperties.class));
            assertEquals(Duration.ofMillis(2000), context.getBean("serviceHttpClient", HttpClient.class).connectTimeout().orElseThrow());
        });
    }

    @Test
    void timeoutDefaultsAreFiniteWhenTheVariablesAreAbsent() {
        Map<String, Object> env = environment();
        env.remove("REST_CLIENT_CONNECT_TIMEOUT_MS");
        env.remove("REST_CLIENT_READ_TIMEOUT_MS");
        AtomicReference<HttpClient> owned = new AtomicReference<>();
        runner(env).run(context -> {
            assertEquals(new RestClientProperties(3000, 5000), context.getBean(RestClientProperties.class));
            owned.set(context.getBean("serviceHttpClient", HttpClient.class));
        });
        assertTrue(owned.get().isTerminated(), "Spring must close the owned HTTP client");
    }

    @ParameterizedTest
    @ValueSource(strings = {"QR_SERVICE_URL", "ORDER_SERVICE_URL", "NOTIFICATION_SERVICE_URL"})
    void missingServiceUrlStopsStartup(String variable) {
        Map<String, Object> env = environment();
        env.remove(variable);
        runner(env).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "QR_SERVICE_URL|qr-service:8080", "QR_SERVICE_URL|ftp://qr-service:8080", "QR_SERVICE_URL|/relative",
            "QR_SERVICE_URL|http://user:secret@qr-service:8080", "QR_SERVICE_URL|''", "QR_SERVICE_URL|http://",
            "ORDER_SERVICE_URL|file:///etc/passwd", "ORDER_SERVICE_URL|http://user@order-service:8080",
            "ORDER_SERVICE_URL|order-service", "NOTIFICATION_SERVICE_URL|notification-service:8080",
            "NOTIFICATION_SERVICE_URL|http://user:secret@notification-service:8080", "NOTIFICATION_SERVICE_URL|ftp://notification-service",
            "REST_CLIENT_CONNECT_TIMEOUT_MS|0", "REST_CLIENT_CONNECT_TIMEOUT_MS|-1", "REST_CLIENT_CONNECT_TIMEOUT_MS|invalid",
            "REST_CLIENT_READ_TIMEOUT_MS|0", "REST_CLIENT_READ_TIMEOUT_MS|-1", "REST_CLIENT_READ_TIMEOUT_MS|invalid"})
    void invalidSettingsStopStartup(String variable, String value) {
        Map<String, Object> env = environment();
        env.put(variable, value);
        runner(env).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void clientsSendNoInventedCredentialsAndUseTheConfiguredPaths() {
        FakeBackends backends = new FakeBackends().installOn(peer);
        UUID restaurant = UUID.randomUUID();
        runner(environment()).run(context -> {
            assertTrue(context.getBean(QrClient.class).listAllTables(restaurant).isEmpty());
            assertTrue(context.getBean(QrClient.class).findTable(restaurant, UUID.randomUUID()).isEmpty());
            assertTrue(context.getBean(OrderClient.class).findOrder(restaurant, UUID.randomUUID()).isEmpty());
            assertTrue(context.getBean(OrderClient.class).activeOrderCounts(restaurant, List.of()).isEmpty());
        });
        assertNotNull(backends);
        assertEquals(3, peer.requests().size(), "An empty session list must not produce a request");
        for (DownstreamStub.Request request : peer.requests()) {
            assertNull(request.header("Authorization"), "No token may be invented before the service identity contract exists");
            assertNull(request.header("Cookie"));
            assertTrue(request.path().startsWith("/api/v1/internal/"), request.path());
        }
    }

    @Test
    void signedGuestValidationSendsTheRawJsonBytesTheCredentialIsBoundTo() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Map<String, Object> env = environment();
        env.put("WAITER_QR_CALLER_KEY_ID", "waiter-key-1");
        env.put("WAITER_QR_CALLER_PRIVATE_KEY", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        env.put("WAITER_QR_CALLER_RESTAURANT_ID", UUID.randomUUID().toString());
        new FakeBackends().installOn(peer);
        runner(env).run(context ->
                assertTrue(context.getBean(QrClient.class).verifyGuest("unknown-guest-token").isEmpty()));

        DownstreamStub.Request sent = peer.requests("POST", FakeBackends.GUEST_VALIDATE).getFirst();
        // A byte[] written through a JSON converter would arrive as a quoted Base64 string, not as an object.
        assertEquals("{\"guestToken\":\"unknown-guest-token\"}", sent.body());
        String credential = sent.header("Authorization");
        assertTrue(credential.startsWith("Service "));
        Claims claims = Jwts.parser().verifyWith((RSAPublicKey) pair.getPublic()).build()
                .parseSignedClaims(credential.substring("Service ".length())).getPayload();
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(sent.body().getBytes(StandardCharsets.UTF_8))), claims.get("body_sha256"));
    }

    @Test
    void everyQrRouteCarriesItsOwnScopeAndOnlyCommandsCarryTheStaffToken() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Map<String, Object> env = environment();
        env.put("WAITER_QR_CALLER_KEY_ID", "waiter-key-1");
        env.put("WAITER_QR_CALLER_PRIVATE_KEY", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        env.put("WAITER_QR_CALLER_RESTAURANT_ID", UUID.randomUUID().toString());
        new FakeBackends().installOn(peer);
        UUID restaurant = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        String staffToken = "staff.access.token";

        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(staff, null, List.of());
        authentication.setDetails(new StaffAccessToken(staffToken));
        context.setAuthentication(authentication);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            runner(env).run(qr -> {
                QrClient client = qr.getBean(QrClient.class);
                // The stub answers for its own data; only what Waiter sent is under test here.
                ignoringAnswer(() -> client.listAllTables(restaurant));
                ignoringAnswer(() -> client.findTable(restaurant, table));
                ignoringAnswer(() -> client.closeSession(new QrCloseRequestDto(command, restaurant, table, session,
                        3L, staff, null, java.time.Instant.parse("2026-11-25T10:00:00Z"))));
                ignoringAnswer(() -> client.closeStatus(restaurant, table, session, command));
                ignoringAnswer(() -> client.markCleaned(new QrCleanRequestDto(command, restaurant, table, 4L, staff,
                        java.time.Instant.parse("2026-11-25T10:00:00Z"))));
                ignoringAnswer(() -> client.cleanStatus(restaurant, table, command));
            });
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        String tables = "/api/v1/internal/tables";
        String sessionPath = tables + "/" + table + "/sessions/" + session;
        assertSigned(pair, staffToken, "GET", tables, "qr:table-read", false,
                tables + "?restaurantId=" + restaurant + "&page=0&size=200");
        assertSigned(pair, staffToken, "GET", tables + "/" + table, "qr:table-read", false,
                tables + "/" + table + "?restaurantId=" + restaurant);
        assertSigned(pair, staffToken, "POST", sessionPath + "/close", "qr:session-close", true,
                sessionPath + "/close");
        assertSigned(pair, staffToken, "GET", sessionPath + "/close-commands/" + command, "qr:session-close", false,
                sessionPath + "/close-commands/" + command + "?restaurantId=" + restaurant);
        assertSigned(pair, staffToken, "POST", tables + "/" + table + "/cleaned", "qr:table-clean", true,
                tables + "/" + table + "/cleaned");
        assertSigned(pair, staffToken, "GET", tables + "/" + table + "/clean-commands/" + command, "qr:table-clean", false,
                tables + "/" + table + "/clean-commands/" + command + "?restaurantId=" + restaurant);
    }

    private static void ignoringAnswer(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException _) {
            // Only the request is inspected; the stub's answer to this synthetic id does not matter.
        }
    }

    private void assertSigned(KeyPair pair, String staffToken, String method, String path, String scope,
                              boolean delegated, String target) throws Exception {
        DownstreamStub.Request sent = peer.requests().stream()
                .filter(r -> r.method().equals(method) && r.path().equals(path)).findFirst().orElseThrow();
        Claims claims = Jwts.parser().verifyWith((RSAPublicKey) pair.getPublic()).build()
                .parseSignedClaims(sent.header("Authorization").substring("Service ".length())).getPayload();
        assertEquals(scope, claims.get("scope"), path);
        assertEquals(method, claims.get("method"), path);
        assertEquals(target, claims.get("target"), path);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(sent.body().getBytes(StandardCharsets.UTF_8))), claims.get("body_sha256"), path);
        assertNull(sent.header("Cookie"));
        assertNull(sent.header("Origin"));
        if (delegated) {
            assertEquals(staffToken, sent.header("X-Restofy-Staff-Delegation"), path);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(staffToken.getBytes(StandardCharsets.UTF_8))), claims.get("staff_sha256"), path);
        } else {
            assertNull(sent.header("X-Restofy-Staff-Delegation"), path);
            assertNull(claims.get("staff_sha256"), path);
        }
    }

    @Test
    void refusedConnectionIsUnavailableNotEmpty() {
        URI unusable = peer.baseUrl();
        peer.close();
        Map<String, Object> env = environment();
        env.put("QR_SERVICE_URL", unusable.toString());
        env.put("ORDER_SERVICE_URL", unusable.toString());
        env.put("NOTIFICATION_SERVICE_URL", unusable.toString());
        runner(env).run(context -> {
            QrClient qrClient = context.getBean(QrClient.class);
            OrderClient orderClient = context.getBean(OrderClient.class);
            NotificationClient notificationClient = context.getBean(NotificationClient.class);
            UUID restaurantId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            DependencyException qr = assertThrows(DependencyException.class, () -> qrClient.listAllTables(restaurantId));
            assertEquals(DependencyFailure.UNAVAILABLE, qr.failure());
            assertEquals("qr", qr.dependency());
            DependencyException order = assertThrows(DependencyException.class,
                    () -> orderClient.findOrder(restaurantId, orderId));
            assertEquals(DependencyFailure.UNAVAILABLE, order.failure());
            assertTrue(!order.getMessage().contains("127.0.0.1"), "Internal addresses must not appear in messages");
            DependencyException notification = assertThrows(DependencyException.class,
                    () -> notificationClient.publish(eventId, "{}"));
            assertEquals(DependencyFailure.UNAVAILABLE, notification.failure());
            assertEquals("notification", notification.dependency());
        });
    }

    @Test
    void clientsRefuseOversizedSessionListsBeforeTheyReachTheNetwork() {
        runner(environment()).run(context -> {
            OrderClient client = context.getBean(OrderClient.class);
            List<UUID> tooMany = IntStream.range(0, OrderClient.MAX_SESSION_IDS + 1)
                    .mapToObj(index -> UUID.randomUUID()).toList();
            UUID restaurant = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class, () -> client.activeOrderCounts(restaurant, tooMany));
            assertThrows(IllegalArgumentException.class, () -> client.search(restaurant, null, tooMany, null, 0, 20));
            List<UUID> noSessions = List.of();
            assertThrows(IllegalArgumentException.class, () -> client.search(restaurant, null, noSessions, null, 0, 20));
        });
        assertTrue(peer.requests().isEmpty());
    }
}
