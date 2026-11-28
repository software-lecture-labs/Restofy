package com.restofy.order.infrastructure.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

@Component
@Profile("order-qr")
class OrderQrRequestSigner {

    private final OrderServiceRequestSigner signer;
    private final UUID restaurantId;

    OrderQrRequestSigner(Clock clock, @Value("${ORDER_QR_CALLER_KEY_ID:}") String keyId,
                         @Value("${ORDER_QR_CALLER_PRIVATE_KEY:}") String encodedKey,
                         @Value("${ORDER_QR_CALLER_RESTAURANT_ID:}") String restaurant) {
        this.signer = new OrderServiceRequestSigner(clock, keyId, encodedKey, "ORDER_QR_CALLER",
                "restofy-qr", "qr:guest-session");
        this.restaurantId = readRestaurant(restaurant);
    }

    UUID restaurantId() {
        return restaurantId;
    }

    String authorization(HttpMethod method, String target, byte[] body) {
        return signer.authorization(restaurantId, method, target, body);
    }

    private static UUID readRestaurant(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equals(value)) {
                return id;
            }
        } catch (IllegalArgumentException _) {
            // Report configuration names without echoing values.
        }
        throw new IllegalStateException("Invalid ORDER_QR_CALLER_RESTAURANT_ID");
    }
}
