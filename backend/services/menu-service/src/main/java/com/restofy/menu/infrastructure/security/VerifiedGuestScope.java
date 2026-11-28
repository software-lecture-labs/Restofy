package com.restofy.menu.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

/**
 * The restaurant QR verified for the current public request, carried as a server-side request attribute. Only
 * {@link PublicGuestScopeInterceptor} can set it (this class is package-private) and a client cannot: request
 * attributes are not headers, cookies or parameters. Reading it is memory access only, so the menu read, which runs in a
 * database transaction, never waits for QR.
 */
final class VerifiedGuestScope {

    private static final String ATTRIBUTE = VerifiedGuestScope.class.getName() + ".restaurant";

    private VerifiedGuestScope() { }

    static void bind(HttpServletRequest request, UUID restaurantId) {
        request.setAttribute(ATTRIBUTE, restaurantId);
    }

    /** The verified restaurant of the current request, empty when none was verified or there is no request. */
    static Optional<UUID> current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof UUID restaurantId) {
            return Optional.of(restaurantId);
        }
        return Optional.empty();
    }
}
