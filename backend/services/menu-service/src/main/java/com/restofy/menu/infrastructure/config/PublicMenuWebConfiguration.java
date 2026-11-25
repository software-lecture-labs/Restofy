package com.restofy.menu.infrastructure.config;

import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.infrastructure.security.PublicGuestScopeInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts the guest-session check in front of the customer menu only ({@code /api/v1/menu/public/**}); the management
 * endpoints never see it. Servlet web applications only, like {@link MenuSecurityConfiguration}.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PublicMenuWebConfiguration implements WebMvcConfigurer {

    private final GuestSessionVerifier verifier;

    PublicMenuWebConfiguration(GuestSessionVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PublicGuestScopeInterceptor(verifier)).addPathPatterns("/api/v1/menu/public/**");
    }
}
