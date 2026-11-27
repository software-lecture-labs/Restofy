package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.client.mock.InMemoryMenuCatalogAdapter;
import com.restofy.order.infrastructure.config.CartProperties;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MenuCatalogHttpAdapterTests {
    private static final String BASE = "https://menu.example";
    private static final String LOOKUP = "/api/v1/internal/menu/catalog/products:lookup";
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final KeyPair KEY = Jwts.SIG.RS256.keyPair().build();
    private static final String PRIVATE_KEY = Base64.getEncoder().encodeToString(KEY.getPrivate().getEncoded());
    private final JsonMapper json = JsonMapper.builder().build();
    private final RestClient.Builder client = RestClient.builder();
    private final MockRestServiceServer peer = MockRestServiceServer.bindTo(client).build();

    private MenuCatalogHttpAdapter adapter() {
        return new MenuCatalogHttpAdapter(BASE, "catalog-key", PRIVATE_KEY, RESTAURANT.toString(),
                new CartProperties(50, 50), Clock.systemUTC(), client, json);
    }

    @Test
    void signsTheExactBatchAndMapsOnlyReturnedProducts() throws Exception {
        UUID missing = UUID.randomUUID();
        var sha256 = MessageDigest.getInstance("SHA-256");
        peer.expect(requestTo(BASE + LOOKUP)).andExpect(request -> {
            byte[] sent = ((MockClientHttpRequest) request).getBodyAsBytes();
            var body = json.readTree(sent);
            assertEquals(RESTAURANT.toString(), body.get("restaurantId").asString());
            assertEquals(2, body.get("productIds").size());
            assertEquals(PRODUCT.toString(), body.get("productIds").get(0).asString());
            assertEquals(missing.toString(), body.get("productIds").get(1).asString());
            String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            assertTrue(header.startsWith("Service "));
            var token = Jwts.parser().verifyWith(KEY.getPublic()).requireIssuer("restofy-order")
                    .requireSubject("order-service").requireAudience("restofy-menu").build()
                    .parseSignedClaims(header.substring("Service ".length()));
            assertEquals("catalog-key", token.getHeader().getKeyId());
            assertEquals("menu:catalog-read", token.getPayload().get("scope"));
            assertEquals(RESTAURANT.toString(), token.getPayload().get("restaurant_id"));
            assertEquals("POST", token.getPayload().get("method"));
            assertEquals(LOOKUP, token.getPayload().get("target"));
            assertEquals(HexFormat.of().formatHex(sha256.digest(sent)),
                    token.getPayload().get("body_sha256"));
        }).andRespond(withSuccess(products(product(PRODUCT, "1300", "1000", "")), MediaType.APPLICATION_JSON));
        var result = adapter().findProducts(RESTAURANT, List.of(PRODUCT, missing, PRODUCT));
        assertEquals(1, result.size());
        assertEquals(1300L, result.get(PRODUCT).basePriceGrossMinor());
        assertTrue(result.get(PRODUCT).sellable());
        assertNull(result.get(PRODUCT).priceVersion());
        peer.verify();
    }

    @Test
    void aKnownButInexactPriceIsNotOrderable() {
        respond(products(product(PRODUCT, "null", "1000", ",\"priceIssue\":\"VAT_EXCLUSIVE\"")));
        respond(products(product(PRODUCT, "1300", "null", "")));
        var adapter = adapter();
        var noGrossPrice = adapter.findProducts(RESTAURANT, List.of(PRODUCT)).get(PRODUCT);
        assertNull(noGrossPrice.basePriceGrossMinor());
        assertFalse(noGrossPrice.sellable());
        assertFalse(adapter.findProducts(RESTAURANT, List.of(PRODUCT)).get(PRODUCT).sellable());
        peer.verify();
    }

    @Test
    void mapsMenusMinimalV1ResponseWithOptionsAndExplicitNulls() {
        UUID extra = UUID.randomUUID();
        UUID ingredient = UUID.randomUUID();
        respond("""
                {"products":[{"productId":"%s","name":"Salad","published":true,"available":true,
                "currency":"TRY","basePriceGrossMinor":1300,"taxRateBps":1000,"priceIssue":null,
                "options":[{"optionId":"%s","name":"Cheese","type":"ADD",
                "priceDeltaMinor":200,"minQuantity":0,"maxQuantity":2,"active":true},
                {"optionId":"%s","name":"Onion","type":"INCLUDED",
                "priceDeltaMinor":0,"minQuantity":0,"maxQuantity":1,"active":false}]}]}
                """.formatted(PRODUCT, extra, ingredient));
        var product = adapter().findProducts(RESTAURANT, List.of(PRODUCT)).get(PRODUCT);
        assertTrue(product.sellable());
        assertNull(product.priceVersion());
        assertEquals(List.of(extra, ingredient), product.options().stream().map(option -> option.optionId()).toList());
        assertEquals(200L, product.options().getFirst().priceDeltaMinor());
        assertEquals(2, product.options().getFirst().maxQuantity());
        assertTrue(product.options().getFirst().active());
        assertFalse(product.options().getLast().active());
        peer.verify();
    }

    @Test
    void protectsRestaurantAndBatchLimitBeforeContactingMenu() {
        var adapter = adapter();
        assertTrue(adapter.findProducts(RESTAURANT, List.of()).isEmpty());
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adapter.findProducts(UUID.randomUUID(),
                        List.of(PRODUCT))).code());
        assertUnavailable(adapter, IntStream.range(0, 51).mapToObj(i -> UUID.randomUUID()).toList());
        peer.verify();
    }

    @Test
    void rejectsUnexpectedProductsAndMalformedPriceDataInsteadOfReturningPartialResults() {
        respond(products(product(UUID.randomUUID(), "1300", "1000", "")));
        respond(products(product(PRODUCT, "-1", "1000", "")));
        respond(products(product(PRODUCT, "null", "1000", "")));
        respond("{}");
        var adapter = adapter();
        for (int i = 0; i < 4; i++) {
            assertUnavailable(adapter, List.of(PRODUCT));
        }
        peer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1299.9", "\"1299\"", "\"\""})
    void rejectsNonIntegerPricesAndTaxInsteadOfCoercingThem(String value) {
        respond(products(product(PRODUCT, value, "1000", "")));
        respond(products(product(PRODUCT, "1300", value, "")));
        var adapter = adapter();
        assertUnavailable(adapter, List.of(PRODUCT));
        assertUnavailable(adapter, List.of(PRODUCT));
        peer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"25.5", "\"25\""})
    void rejectsNonIntegerOptionPriceDifferences(String value) {
        respond("""
                {"products":[{"productId":"%s","name":"Salad","published":true,"available":true,
                "currency":"TRY","basePriceGrossMinor":1300,"taxRateBps":1000,"priceIssue":null,
                "options":[{"optionId":"%s","name":"Cheese","type":"ADD","priceDeltaMinor":%s,
                "minQuantity":0,"maxQuantity":2,"active":true}]}]}
                """.formatted(PRODUCT, UUID.randomUUID(), value));
        assertUnavailable(adapter(), List.of(PRODUCT));
        peer.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 403, 413, 415, 429, 503})
    void peerErrorsAreDependencyFailures(int httpStatus) {
        peer.expect(requestTo(BASE + LOOKUP)).andRespond(withStatus(HttpStatus.valueOf(httpStatus)));
        assertUnavailable(adapter(), List.of(PRODUCT));
        peer.verify();
    }

    @Test
    void explicitlyEnabledMenuProfileUsesOnlyTheHttpAdapter() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test", "order-menu"))
                .withUserConfiguration(MenuCatalogHttpAdapter.class, UnavailableMenuCatalogAdapter.class,
                        InMemoryMenuCatalogAdapter.class)
                .withBean(CartProperties.class, () -> new CartProperties(50, 50))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(JsonMapper.class, JsonMapper::new)
                .withPropertyValues("MENU_SERVICE_URL=" + BASE, "ORDER_MENU_CALLER_KEY_ID=catalog-key",
                        "ORDER_MENU_CALLER_PRIVATE_KEY=" + PRIVATE_KEY, "ORDER_MENU_CALLER_RESTAURANT_ID=" + RESTAURANT)
                .run(context -> assertEquals(List.of(MenuCatalogHttpAdapter.class),
                        context.getBeansOfType(MenuCatalogPort.class)
                                .values().stream().map(Object::getClass).toList()));
    }

    private void respond(String body) {
        peer.expect(requestTo(BASE + LOOKUP)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static void assertUnavailable(MenuCatalogHttpAdapter adapter, List<UUID> ids) {
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adapter.findProducts(RESTAURANT, ids)).code());
    }

    private static String products(String product) {
        return "{\"products\":[" + product + "]}";
    }

    private static String product(UUID id, String amount, String vat, String extraJson) {
        return """
                {"productId":"%s","name":"Salad","published":true,"available":true,"currency":"TRY",
                "basePriceGrossMinor":%s,"taxRateBps":%s,"options":[]%s}
                """.formatted(id, amount, vat, extraJson);
    }
}
