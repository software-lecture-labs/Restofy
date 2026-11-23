package com.restofy.order.application.port;

import com.restofy.order.domain.enums.ServiceName;

import java.util.Objects;
import java.util.UUID;

/**
 * A verified calling service. The QR lifecycle credential also binds a tenant and the exact HTTP request.
 */
public record ServiceActor(ServiceName name, QrRequest qrRequest) {

    public record QrRequest(UUID restaurantId, String method, String target, String bodyDigest) {
    }

    public ServiceActor(ServiceName name) {
        this(name, null);
    }

    public ServiceActor {
        Objects.requireNonNull(name, "name");
    }
}
