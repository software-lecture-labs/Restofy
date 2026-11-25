package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The production {@link PublicRestaurantScopeResolver}. A restaurant is trusted only if QR's guest-session verification
 * vouched for the current request ({@link VerifiedGuestScope}) <b>and</b> the URL names that same restaurant. A client
 * value alone, a cookie, a header or a UUID that merely exists never counts, and a URL naming another restaurant than the
 * verified one is the same "not verified" answer, so a guest can only ever read the menu of their own restaurant.
 *
 * <p>Until a real {@code GuestSessionVerifier} is connected the default one verifies nothing, so this resolver answers
 * empty for every reference and the public menu stays fail-closed (404), exactly as the earlier deny-all resolver did.
 * Do not turn this into "the reference is the restaurant id" or a header reader.
 */
@Component
class GuestSessionRestaurantScopeResolver implements PublicRestaurantScopeResolver {

    private static final int UUID_LENGTH = 36;

    @Override
    public Optional<UUID> resolve(String restaurantRef) {
        return canonicalUuid(restaurantRef).flatMap(claimed -> VerifiedGuestScope.current().filter(claimed::equals));
    }

    /** The UUID a reference spells in its standard 36-character form, empty for any other text. */
    static Optional<UUID> canonicalUuid(String reference) {
        if (reference == null || reference.length() != UUID_LENGTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(reference));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
