package com.restofy.menu.application.security;

import java.util.Optional;

/**
 * Integration point with the (not yet implemented) Auth contract. Empty means "not authenticated".
 * The production implementation fails closed until Auth is connected.
 */
public interface CurrentActorProvider {

    Optional<AuthenticatedActor> current();
}
