package com.restofy.menu.application.security;

import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.exception.ForbiddenOperationException;
import com.restofy.menu.domain.exception.UnauthenticatedException;

/**
 * The single place that turns "a verified actor" into "an ADMIN who may manage one restaurant".
 * Product management is tenant-scoped through the actor only; nothing from the request is used.
 */
public final class AdminAccess {

    private AdminAccess() { }

    /** Returns the actor when it is a fully identified ADMIN; throws 401 / 403 otherwise. */
    public static AuthenticatedActor requireAdmin(AuthenticatedActor actor) {
        if (actor == null || actor.restaurantId() == null || actor.userId() == null) {
            throw new UnauthenticatedException();
        }
        if (actor.role() != ActorRole.ADMIN) {
            throw new ForbiddenOperationException();
        }
        return actor;
    }
}
