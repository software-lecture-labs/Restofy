package com.restofy.order.infrastructure.client;

import com.restofy.order.infrastructure.config.KitchenRestaurantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("order-kitchen")
public class KitchenEventsHttpClient {

    private static final String TARGET = "/api/v1/internal/kitchen/order-events";
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final RestClient http;
    private final URI endpoint;
    private final OrderServiceRequestSigner signer;
    private final Set<UUID> restaurants;

    KitchenEventsHttpClient(RestClient.Builder client, Clock clock, KitchenRestaurantScope scope,
                            @Value("${KITCHEN_SERVICE_URL:}") String base,
                            @Value("${ORDER_KITCHEN_CALLER_KEY_ID:}") String keyId,
                            @Value("${ORDER_KITCHEN_CALLER_PRIVATE_KEY:}") String privateKey) {
        this.http = client.build();
        this.endpoint = QrSessionHttpAdapter.httpsOrigin(base, "KITCHEN_SERVICE_URL", "order-kitchen").resolve(TARGET);
        this.signer = new OrderServiceRequestSigner(clock, keyId, privateKey, "ORDER_KITCHEN_CALLER",
                "restofy-kitchen", "kitchen:order-events");
        this.restaurants = Set.copyOf(scope.restaurants());
    }

    public int send(UUID restaurantId, byte[] body) {
        if (!restaurants.contains(restaurantId) || body.length == 0 || body.length > MAX_BODY_BYTES) {
            return 400;
        }
        return http.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, signer.authorization(restaurantId, HttpMethod.POST, TARGET, body))
                .body(body).exchange((request, response) -> response.getStatusCode().value());
    }
}
