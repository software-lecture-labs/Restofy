package com.restofy.menu.support;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.security.CurrentActorProvider;
import com.restofy.menu.domain.enums.ActorRole;

import java.util.Optional;
import java.util.UUID;

/**
 * Test-only actor source (lives in src/test, never packaged). Tests set the "verified" actor
 * explicitly; nothing here reads request headers or parameters.
 */
public class TestActorProvider implements CurrentActorProvider {

    private volatile AuthenticatedActor actor;

    public void actAs(UUID restaurantId, ActorRole role) {
        this.actor = new AuthenticatedActor(UUID.randomUUID(), restaurantId, role);
    }

    public void actAsNobody() {
        this.actor = null;
    }

    @Override
    public Optional<AuthenticatedActor> current() {
        return Optional.ofNullable(actor);
    }
}
