package com.restofy.menu.integration;

import com.restofy.menu.application.security.CurrentActorProvider;
import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import com.restofy.menu.application.security.TrustedKitchenCallerProvider;
import com.restofy.menu.application.service.RatingSummaryPort;
import com.restofy.menu.support.PublicMenuFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MENU-INT-06 · what a caller can really do against Menu as it is wired for production, over a real socket.
 *
 * <p>A real embedded server and real PostgreSQL, <b>no test stand-ins imported</b>: the staff actor, the customer
 * restaurant resolver, the Kitchen caller and the Rating source are the fail-closed defaults that ship today. No other
 * service is running, and Menu has no client to any of them, so this proves the opposite of an end-to-end flow: that
 * every integration point that still waits for a team decision (Auth key, QR verification, D-09 service identity,
 * Order and Kitchen endpoints) is closed and stays closed under forged headers. A route that opens up (answers 2xx or
 * fails with 5xx) or a verifier that vouches for a forged identity fails these tests; a correctly protected new route
 * still answers 401/403 and needs its own contract test. It is a boot smoke test, not an end-to-end test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1"})
class ProductionWiringHttpSmokeTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired TrustedKitchenCallerProvider kitchenCallers;
    @Autowired PublicRestaurantScopeResolver publicScopes;
    @Autowired RatingSummaryPort ratings;

    private PublicMenuFixture fixture;
    private UUID product;
    private UUID category;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        category = fixture.root(RESTAURANT, "Main");
        product = fixture.visible(RESTAURANT, category, "Pide");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    private HttpResponse<String> call(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        request.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return HTTP.send(request.build(), BodyHandlers.ofString());
    }

    private static String[] forged() {
        return new String[] {"X-Role", "ADMIN", "X-Restaurant-Id", RESTAURANT.toString(), "X-User-Id", UUID.randomUUID().toString(),
                "X-Service-Name", "kitchen-service", "Authorization", "Service forged", "Content-Type", "application/json"};
    }

    private String availability() {
        return jdbc.queryForObject("SELECT status || '/' || version FROM product_availability WHERE product_id = ?", String.class, product);
    }

    @Test
    void theServerIsUpAndTheHealthEndpointShowsNothingBeyondTheStatus() throws Exception {
        HttpResponse<String> health = call("GET", "/actuator/health", null);
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"UP\""), health.body());
        for (String detail : new String[] {"jdbc", "postgres", "components", "diskSpace", "url"}) {
            assertFalse(health.body().toLowerCase().contains(detail.toLowerCase()), "health shows " + detail);
        }
    }

    @Test
    void theWiringThatShipsTodayIsTheFailClosedOne() {
        assertTrue(actors.current().isEmpty());
        assertTrue(kitchenCallers.current().isEmpty());
        assertTrue(publicScopes.resolve(RESTAURANT.toString()).isEmpty());
        assertTrue(publicScopes.resolve("alpha-menu").isEmpty());
        assertEquals("UNAVAILABLE", ratings.summaryFor(RESTAURANT, product).status().name());
        for (Object bean : List.of(actors, kitchenCallers, publicScopes, ratings)) {
            assertTrue(bean.getClass().getName().startsWith("com.restofy.menu.infrastructure."),
                    bean.getClass().getName() + " must be a production class, never a src/test stand-in");
        }
    }

    @Test
    void everyStaffRouteRefusesAnUnidentifiedCallerEvenWithForgedIdentityHeaders() throws Exception {
        String id = product.toString();
        String json = "{\"name\":\"x\",\"version\":0}";
        String[][] routes = {
                {"GET", "/api/v1/menu/categories"}, {"GET", "/api/v1/menu/categories/" + category},
                {"GET", "/api/v1/menu/products"}, {"GET", "/api/v1/menu/products/" + id},
                {"GET", "/api/v1/menu/products/" + id + "/options"},
                {"POST", "/api/v1/menu/categories"}, {"POST", "/api/v1/menu/products"},
                {"PATCH", "/api/v1/menu/products/" + id}, {"PATCH", "/api/v1/menu/products/" + id + "/availability"},
                {"PATCH", "/api/v1/menu/products/" + id + "/publication"}, {"PATCH", "/api/v1/menu/products/" + id + "/active"},
                {"DELETE", "/api/v1/menu/products/" + id}};
        String before = availability();
        for (String[] route : routes) {
            HttpResponse<String> response = call(route[0], route[1], "GET".equals(route[0]) ? null : json, forged());
            int status = response.statusCode();
            assertTrue(status == 401 || status == 403, route[0] + " " + route[1] + " answered " + status);
            assertFalse(response.body().contains(RESTAURANT.toString()), "the body must not echo the claimed restaurant");
            assertFalse(response.body().contains("Pide"), "the body must carry no menu data");
        }
        assertEquals(before, availability(), "nothing was written");
    }

    @Test
    void theCustomerMenuStaysClosedEvenForARealRestaurantWithRealData() throws Exception {
        String unknownBody = call("GET", "/api/v1/menu/public/restaurants/" + UUID.randomUUID() + "/categories", null).body();
        String guestCookie = "restofy_guest=" + "A".repeat(43);
        for (String ref : new String[] {RESTAURANT.toString(), "alpha-menu"}) {
            for (String tail : new String[] {"/categories", "/products", "/products/" + product}) {
                HttpResponse<String> response = call("GET", "/api/v1/menu/public/restaurants/" + ref + tail, null, "Cookie", guestCookie,
                        "X-Restaurant-Id", RESTAURANT.toString());
                assertEquals(404, response.statusCode(), ref + tail);
                assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
                assertFalse(response.body().contains("Pide"), "no menu data without a verified restaurant");
            }
        }
        HttpResponse<String> real = call("GET", "/api/v1/menu/public/restaurants/" + RESTAURANT + "/categories", null);
        assertEquals(unknownBody.replaceAll("\"instance\":\"[^\"]*\",?", ""), real.body().replaceAll("\"instance\":\"[^\"]*\",?", ""),
                "a restaurant that exists answers exactly like one that does not");
    }

    @Test
    void theCustomerRoutesAcceptNothingButReads() throws Exception {
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            int status = call(method, "/api/v1/menu/public/restaurants/" + RESTAURANT + "/products", "{}", forged()).statusCode();
            assertTrue(status >= 400 && status < 500, method + " answered " + status);
        }
    }

    @Test
    void noInternalRouteOfAnyTeamsProposalExistsAndNoneOfThemProcessesAnything() throws Exception {
        String before = availability();
        String command = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product
                + "\",\"targetStatus\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\"}";
        String lookup = "{\"restaurantId\":\"" + RESTAURANT + "\",\"productIds\":[\"" + product + "\"]}";
        String[] paths = {
                "/api/v1/internal/menu/catalog/products:lookup",  // Order's proposal (order-request-01)
                "/api/v1/internal/menu/availability-commands", "/api/v1/internal/menu/closed-products",  // Kitchen's (kitchen-request-01)
                "/api/v1/internal/qr/guest-sessions/validate",  // QR's route; Menu is a caller, never a host of it
                "/internal/v1/menu/catalog", "/internal/v1/menu/availability-commands", "/api/v1/menu/internal/catalog",
                "/api/v1/menu/internal/availability-commands", "/api/v1/menu/availability-commands"};
        for (String path : paths) {
            for (String method : new String[] {"GET", "POST", "PUT"}) {
                HttpResponse<String> response = call(method, path, "GET".equals(method) ? null : (path.contains("catalog") ? lookup : command),
                        forged());
                int status = response.statusCode();
                assertTrue(status == 401 || status == 403 || status == 404 || status == 405, method + " " + path + " answered " + status);
                assertFalse(response.body().contains("Pide"), "no product data over an internal route");
            }
        }
        assertEquals(before, availability(), "no forged command changed anything");
    }

    @Test
    void errorsAndDisabledToolingGiveAwayNothing() throws Exception {
        for (String path : new String[] {"/api/v1/menu/products/%00", "/api/v1/menu/products/not-a-uuid", "/api/v1/menu/" + "a".repeat(3000),
                "/v3/api-docs", "/swagger-ui/index.html", "/actuator/env", "/actuator/beans"}) {
            HttpResponse<String> response = call("GET", path, null);
            assertTrue(response.statusCode() != 200 && response.statusCode() < 500, path + " answered " + response.statusCode());
            for (String leak : new String[] {"Exception", "at com.restofy", "stackTrace", "jdbc:", "POSTGRES_PASSWORD"}) {
                assertFalse(response.body().contains(leak), path + " leaks " + leak);
            }
        }
    }
}
