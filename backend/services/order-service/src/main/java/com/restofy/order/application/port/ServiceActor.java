package com.restofy.order.application.port;

import com.restofy.order.domain.enums.ServiceName;

import java.util.Objects;
import java.util.UUID;

/**
 * A verified calling service. Signed credentials bind the tenant and the exact HTTP request.
 */
public record ServiceActor(ServiceName name, SignedRequest signedRequest) {

    public record SignedRequest(UUID restaurantId, String method, String target, String bodyDigest) {
    }

    public ServiceActor(ServiceName name) {
        this(name, null);
    }

    public ServiceActor {
        Objects.requireNonNull(name, "name");
    }

    public boolean isKitchenFor(UUID restaurantId) {
        return name == ServiceName.KITCHEN
                && (signedRequest == null || signedRequest.restaurantId().equals(restaurantId));
    }
}
