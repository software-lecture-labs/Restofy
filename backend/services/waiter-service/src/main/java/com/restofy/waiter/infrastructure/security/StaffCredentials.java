package com.restofy.waiter.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * The staff access credential of the request being handled, for the one purpose of acting for that staff
 * member towards Order (reads with their session, serve commands as a delegation). It is held only as a request
 * attribute that {@link StaffCookieAuthenticationFilter} sets after Auth confirmed the session; it is never
 * stored, cached, logged or part of any response, and it ends with the request.
 */
public final class StaffCredentials {

    static final String ATTRIBUTE = StaffCredentials.class.getName() + ".CURRENT";

    private StaffCredentials() {
    }

    static void remember(HttpServletRequest request, String credential) {
        request.setAttribute(ATTRIBUTE, credential);
    }

    /** The verified credential of the current request, empty outside a staff request. */
    public static Optional<String> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object value = attributes == null ? null
                : attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof String credential ? Optional.of(credential) : Optional.empty();
    }
}
