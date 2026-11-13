package com.restofy.menu.application.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The verified party behind a Kitchen availability command: the restaurant it may act in and the staff member it acts
 * for (recorded as the author of the change). It exists only after a trusted mechanism has authenticated the calling
 * <b>service</b>; nothing a client sends in a header, parameter or body can create one. A staff JWT with the KITCHEN
 * role is a person, not the Kitchen service, and never produces this.
 */
public record TrustedKitchenCaller(UUID restaurantId, UUID onBehalfOfUserId) {

    public TrustedKitchenCaller {
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(onBehalfOfUserId, "onBehalfOfUserId");
    }
}
