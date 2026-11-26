package com.restofy.kitchen.infrastructure.security;

import java.io.Serializable;
import java.util.UUID;

/**
 * Verified identity of the caller. The tenant comes from here, never from request parameters or headers.
 * Serializable because it is held by a Spring Security authentication, which is.
 */
public record KitchenPrincipal(UUID userId, UUID restaurantId) implements Serializable {
}
