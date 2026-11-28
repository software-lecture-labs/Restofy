package com.restofy.order.infrastructure.client;

import com.restofy.order.infrastructure.config.KitchenRestaurantScope;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class KitchenEventsHttpClientTests {

    private static final String TARGET = "/api/v1/internal/kitchen/order-events";
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final KeyPair KEY = Jwts.SIG.RS256.keyPair().build();
    private static final String PRIVATE_KEY = Base64.getEncoder().encodeToString(KEY.getPrivate().getEncoded());

    @Test
    void signsTheExactBytesForTheConfiguredKitchenOriginAndTenant() throws Exception {
        var builder = RestClient.builder();
        var peer = MockRestServiceServer.bindTo(builder).build();
        byte[] body = "{\"eventType\":\"OrderAccepted\",\"productName\":\"Soğan\"}"
                .getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        peer.expect(requestTo("https://kitchen.example" + TARGET))
                .andExpect(method(HttpMethod.POST)).andExpect(content().bytes(body))
                .andExpect(request -> {
                    String header = request.getHeaders().getFirst("Authorization");
                    var jwt = Jwts.parser().verifyWith(KEY.getPublic()).requireAudience("restofy-kitchen").build()
                            .parseSignedClaims(header.substring("Service ".length()));
                    assertEquals("kitchen-key", jwt.getHeader().getKeyId());
                    var claims = jwt.getPayload();
                    assertEquals("restofy-order", claims.getIssuer());
                    assertEquals("order-service", claims.getSubject());
                    assertEquals("kitchen:order-events", claims.get("scope"));
                    assertEquals(RESTAURANT.toString(), claims.get("restaurant_id"));
                    assertEquals("POST", claims.get("method"));
                    assertEquals(TARGET, claims.get("target"));
                    assertEquals(digest, claims.get("body_sha256"));
                }).andRespond(withNoContent());

        var client = client(builder, "https://kitchen.example");
        assertEquals(400, client.send(UUID.randomUUID(), body));
        assertEquals(204, client.send(RESTAURANT, body));
        peer.verify();
    }

    @Test
    void requiresAnHttpsOrigin() {
        assertThrows(IllegalStateException.class, () -> client(RestClient.builder(), "http://kitchen.example"));
        assertThrows(IllegalStateException.class, () -> client(RestClient.builder(), "https://kitchen.example/path"));
    }

    private static KitchenEventsHttpClient client(RestClient.Builder builder, String origin) {
        return new KitchenEventsHttpClient(builder, Clock.systemUTC(), new KitchenRestaurantScope(RESTAURANT.toString()),
                origin, "kitchen-key", PRIVATE_KEY);
    }
}
