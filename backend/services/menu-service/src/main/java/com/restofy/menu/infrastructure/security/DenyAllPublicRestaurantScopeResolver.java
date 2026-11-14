package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Fail-closed default: no reference is ever trusted, so every public menu request answers 404 until the QR /
 * Admin verification contract exists and replaces this resolver. Do not turn this into "the reference is the
 * restaurant id" or a header reader: that would present an unverified client value as a verified restaurant.
 */
@Component
class DenyAllPublicRestaurantScopeResolver implements PublicRestaurantScopeResolver {

    @Override
    public Optional<UUID> resolve(String restaurantRef) {
        return Optional.empty();
    }
}
