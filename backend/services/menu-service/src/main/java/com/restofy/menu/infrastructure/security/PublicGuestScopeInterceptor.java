package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.GuestSessionVerification;
import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.application.security.GuestToken;
import com.restofy.menu.domain.exception.PublicMenuUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Asks the {@link GuestSessionVerifier} once per public menu request, before the controller and before any database
 * transaction, and records what it vouched for. Nothing is asked unless the request is worth asking about: a guest
 * credential must be present and well-formed, and the restaurant in the URL must be a canonical UUID (anything else can
 * never equal a verified restaurant, so QR is not bothered). The URL value is only a claim; the restaurant that counts
 * is the verified one (see {@link GuestSessionRestaurantScopeResolver}).
 *
 * <p>A verifier that cannot decide, or throws, ends the request as {@link PublicMenuUnavailableException} (503). A
 * refusal binds nothing and the read ends in the usual 404. Nothing here logs the credential or the URL value.
 */
public final class PublicGuestScopeInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PublicGuestScopeInterceptor.class);
    private static final String REFERENCE_VARIABLE = "restaurantRef";

    private final GuestSessionVerifier verifier;

    public PublicGuestScopeInterceptor(GuestSessionVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Optional<GuestToken> token = PublicGuestCookie.single(request);
        if (token.isEmpty() || claimedRestaurant(request).isEmpty()) {
            return true;
        }
        GuestSessionVerification result = verify(token.get());
        if (result instanceof GuestSessionVerification.Verified verified) {
            VerifiedGuestScope.bind(request, verified.restaurantId());
        } else if (result instanceof GuestSessionVerification.Unavailable) {
            throw new PublicMenuUnavailableException();
        }
        return true;
    }

    /** Anything but a clear answer is "unavailable": a failing verifier is never read as a success or a refusal. */
    private GuestSessionVerification verify(GuestToken token) {
        try {
            GuestSessionVerification result = verifier.verify(token);
            return result == null ? new GuestSessionVerification.Unavailable() : result;
        } catch (RuntimeException e) {
            LOG.warn("Guest session verification failed ({}).", e.getClass().getName());
            return new GuestSessionVerification.Unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<UUID> claimedRestaurant(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String reference = variables instanceof Map<?, ?> map ? (String) ((Map<String, String>) map).get(REFERENCE_VARIABLE) : null;
        return GuestSessionRestaurantScopeResolver.canonicalUuid(reference);
    }
}
