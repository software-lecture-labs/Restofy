package com.restofy.menu.application.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The verified party behind a catalog lookup of the Order service: the restaurant it may read. It exists only after a
 * trusted mechanism has authenticated the calling <b>service</b>; nothing a client sends in a header, parameter or body
 * can create one. A staff JWT or a guest session is a person, not the Order service, and never produces this.
 */
public record TrustedOrderCaller(UUID restaurantId) {

    public TrustedOrderCaller {
        Objects.requireNonNull(restaurantId, "restaurantId");
    }
}
