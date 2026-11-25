package com.restofy.menu.infrastructure.config;

import com.restofy.menu.application.ratelimit.RateLimiter;
import com.restofy.menu.application.security.CurrentActorProvider;
import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import com.restofy.menu.infrastructure.ratelimit.AdminRateLimitInterceptor;
import com.restofy.menu.infrastructure.ratelimit.PublicRateLimitInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts the limits in front of the two kinds of Menu endpoint: the customer menu and the management endpoints. The path
 * patterns are Spring's own (the ones that pick the controller), so a path cannot reach a controller without being
 * counted by the matching interceptor. The order is explicit and late: an interceptor that verifies a guest session
 * must run first so the customer budget is chosen from the verified restaurant. Servlet web applications only.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RateLimitWebConfiguration implements WebMvcConfigurer {

    static final int ORDER = 100;
    private static final String PUBLIC_PATH = "/api/v1/menu/public/**";

    private final RateLimiter limiter;
    private final PublicRestaurantScopeResolver scope;
    private final CurrentActorProvider actors;

    RateLimitWebConfiguration(RateLimiter limiter, PublicRestaurantScopeResolver scope, CurrentActorProvider actors) {
        this.limiter = limiter;
        this.scope = scope;
        this.actors = actors;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PublicRateLimitInterceptor(limiter, scope)).addPathPatterns(PUBLIC_PATH).order(ORDER);
        registry.addInterceptor(new AdminRateLimitInterceptor(limiter, actors)).addPathPatterns("/api/v1/menu/**")
                .excludePathPatterns(PUBLIC_PATH).order(ORDER);
    }
}
