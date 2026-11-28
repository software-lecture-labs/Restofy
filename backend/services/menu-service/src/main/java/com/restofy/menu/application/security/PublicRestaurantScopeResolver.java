package com.restofy.menu.application.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns the restaurant reference of a public menu request into a restaurant id <b>that was verified by a
 * trusted source</b> (the QR / table-session verification or the Admin restaurant registry, neither of which
 * exists yet). The reference is client input and proves nothing by itself, so an implementation must verify it
 * and answer empty for anything it cannot vouch for, never guess.
 *
 * <p>The production implementation ({@code GuestSessionRestaurantScopeResolver}) trusts only a restaurant that QR's
 * guest-session verification ({@link GuestSessionVerifier}) vouched for in the web layer of the same request, and only if
 * the URL names that restaurant. The verifier is not connected to QR yet, so in production nothing is verified and every
 * reference is empty (fail-closed). Resolving a restaurant grants read access to its public menu only: never an order, a
 * table session or any staff authority.
 */
public interface PublicRestaurantScopeResolver {

    Optional<UUID> resolve(String restaurantRef);
}
