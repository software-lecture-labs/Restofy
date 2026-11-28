package com.restofy.order.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
@Profile("order-kitchen")
public class KitchenRestaurantScope {

    private final List<UUID> restaurants;

    public KitchenRestaurantScope(@Value("${ORDER_KITCHEN_SERVICE_RESTAURANTS:}") String configured) {
        try {
            restaurants = Arrays.stream(configured.split(",", -1)).map(String::trim)
                    .map(KitchenRestaurantScope::restaurantId).distinct().toList();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("ORDER_KITCHEN_SERVICE_RESTAURANTS must list authorised restaurant UUIDs");
        }
    }

    public List<UUID> restaurants() {
        return restaurants;
    }

    private static UUID restaurantId(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("Invalid restaurant UUID");
        }
        return id;
    }
}
