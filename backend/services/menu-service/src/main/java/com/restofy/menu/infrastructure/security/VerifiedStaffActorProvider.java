package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.security.CurrentActorProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The production {@link CurrentActorProvider}: the actor is the one {@link StaffCookieAuthenticationFilter} produced
 * from a token Auth signed, and nothing else. A caller the framework merely considers logged in (a form or Basic
 * login, a test user) carries no {@link AuthenticatedActor} and is therefore not an actor; with no verified token,
 * no configured key or outside a request the answer is empty and every management endpoint says 401. Do not turn this
 * into a fixed admin, a header reader or a parameter reader.
 */
@Component
class VerifiedStaffActorProvider implements CurrentActorProvider {

    @Override
    public Optional<AuthenticatedActor> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedActor actor) {
            return Optional.of(actor);
        }
        return Optional.empty();
    }
}
