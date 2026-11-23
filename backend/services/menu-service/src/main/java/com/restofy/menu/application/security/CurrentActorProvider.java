package com.restofy.menu.application.security;

import java.util.Optional;

/**
 * Source of the verified staff member behind the current management request. Empty means "not authenticated". The
 * production implementation only ever returns the actor proved from a token Auth signed (the {@code staff-v1} cookie
 * contract); it fails closed (empty) for everything else, including a missing or unconfigured verification key.
 */
public interface CurrentActorProvider {

    Optional<AuthenticatedActor> current();
}
