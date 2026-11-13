package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.security.CurrentActorProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fail-closed default: no caller is ever authenticated, so every category endpoint answers 401
 * until the Auth contract (D-09) is implemented and replaces this provider. Do not turn this into
 * a fixed admin or a header reader.
 */
@Component
class UnauthenticatedCurrentActorProvider implements CurrentActorProvider {

    @Override
    public Optional<AuthenticatedActor> current() {
        return Optional.empty();
    }
}
