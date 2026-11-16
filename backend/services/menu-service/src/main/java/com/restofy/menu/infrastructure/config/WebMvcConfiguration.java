package com.restofy.menu.infrastructure.config;

import com.restofy.menu.application.security.CurrentActorProvider;
import com.restofy.menu.infrastructure.security.AdminActorArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers the service-local argument resolver. No security filter, CORS or CSRF rule is touched. */
@Configuration
class WebMvcConfiguration implements WebMvcConfigurer {

    private final CurrentActorProvider actors;

    WebMvcConfiguration(CurrentActorProvider actors) {
        this.actors = actors;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AdminActorArgumentResolver(actors));
    }
}
