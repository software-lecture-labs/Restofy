package com.restofy.menu.infrastructure.ratelimit;

import com.restofy.menu.application.ratelimit.RateLimitScope;
import com.restofy.menu.application.ratelimit.RateLimitSubjects;
import com.restofy.menu.application.ratelimit.RateLimiter;
import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Counts every customer-menu request before it is read. A request whose restaurant the trusted resolver verified counts
 * against that restaurant's own budget, so one restaurant's customers can never use up another's. A request nobody could
 * verify counts against the coarse peer budget instead, which is the only one a client without a verified session can
 * reach: it cannot spend a verified restaurant's budget, and a verified restaurant cannot spend its. The restaurant in
 * the URL is never a counter key by itself, only what the resolver verified from it.
 *
 * <p>The resolver is the same one the menu read will ask; it must stay free of network calls (the guest-session check
 * belongs in an earlier interceptor that records its verdict). Anything it throws counts as "not verified".
 */
public final class PublicRateLimitInterceptor implements HandlerInterceptor {

    private static final String REFERENCE_VARIABLE = "restaurantRef";
    private static final int MAX_REFERENCE_LENGTH = 128;

    private final RateLimiter limiter;
    private final PublicRestaurantScopeResolver scope;

    public PublicRateLimitInterceptor(RateLimiter limiter, PublicRestaurantScopeResolver scope) {
        this.limiter = limiter;
        this.scope = scope;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Optional<UUID> restaurant = verifiedRestaurant(request);
        if (restaurant.isPresent()) {
            limiter.require(RateLimitScope.PUBLIC_RESTAURANT, RateLimitSubjects.restaurant(restaurant.get()));
        } else {
            limiter.require(RateLimitScope.PUBLIC_UNVERIFIED, RateLimitSubjects.peer(request.getRemoteAddr()));
        }
        return true;
    }

    private Optional<UUID> verifiedRestaurant(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object reference = variables instanceof Map<?, ?> map ? map.get(REFERENCE_VARIABLE) : null;
        if (!(reference instanceof String text) || text.isBlank() || text.length() > MAX_REFERENCE_LENGTH) {
            return Optional.empty();
        }
        try {
            return scope.resolve(text);
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }
}
