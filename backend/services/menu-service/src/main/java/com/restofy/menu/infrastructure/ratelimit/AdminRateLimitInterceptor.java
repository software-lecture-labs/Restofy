package com.restofy.menu.infrastructure.ratelimit;

import com.restofy.menu.application.ratelimit.RateLimitScope;
import com.restofy.menu.application.ratelimit.RateLimitSubjects;
import com.restofy.menu.application.ratelimit.RateLimiter;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.security.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Counts every management request before it is handled, reads and writes apart (a write is the riskier one and gets the
 * smaller budget). The actor comes from the trusted {@link CurrentActorProvider}, never from a header: it is counted both
 * on its own budget, so one user cannot use up the others', and on its restaurant's, so no restaurant can use up more than
 * its share and another restaurant is never affected. A user who is over their own budget does not spend the
 * restaurant's. A request with no verified actor is counted against the coarse peer budget, never skipped.
 *
 * <p>This runs after Spring Security, so requests rejected there (no login, a failed CSRF check) are not counted: they
 * cost no database work and are not an authenticated user's doing.
 */
public final class AdminRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;
    private final CurrentActorProvider actors;

    public AdminRateLimitInterceptor(RateLimiter limiter, CurrentActorProvider actors) {
        this.limiter = limiter;
        this.actors = actors;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        Optional<AuthenticatedActor> actor = currentActor();
        if (actor.isEmpty()) {
            limiter.require(RateLimitScope.ADMIN_UNVERIFIED, RateLimitSubjects.peer(request.getRemoteAddr()));
            return true;
        }
        AuthenticatedActor verified = actor.get();
        limiter.require(read ? RateLimitScope.ADMIN_READ : RateLimitScope.ADMIN_WRITE,
                RateLimitSubjects.actor(verified.restaurantId(), verified.userId()));
        limiter.require(read ? RateLimitScope.ADMIN_RESTAURANT_READ : RateLimitScope.ADMIN_RESTAURANT_WRITE,
                RateLimitSubjects.restaurant(verified.restaurantId()));
        return true;
    }

    private Optional<AuthenticatedActor> currentActor() {
        try {
            return actors.current();
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }
}
