package com.restofy.menu.application.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns the restaurant reference of a public menu request into a restaurant id <b>that was verified by a
 * trusted source</b> (the QR / table-session verification or the Admin restaurant registry, neither of which
 * exists yet). The reference is client input and proves nothing by itself, so an implementation must verify it
 * and answer empty for anything it cannot vouch for, never guess.
 *
 * <p>The production implementation is fail-closed (always empty) until a real resolver is connected. Resolving a
 * restaurant grants read access to its public menu only: never an order, a table session or any staff authority.
 */
public interface PublicRestaurantScopeResolver {

    Optional<UUID> resolve(String restaurantRef);
}
