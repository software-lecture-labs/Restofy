package com.restofy.menu.application.security;

import com.restofy.menu.domain.enums.ActorRole;

import java.util.UUID;

/**
 * A verified staff member acting for exactly one restaurant. It must only ever be produced by a
 * trusted {@link CurrentActorProvider}; request bodies, query parameters and client-supplied
 * headers (X-Restaurant-Id, X-Role, X-User-Id) are never a source for it.
 */
public record AuthenticatedActor(UUID userId, UUID restaurantId, ActorRole role) {
}
