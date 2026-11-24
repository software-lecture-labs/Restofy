package com.restofy.waiter.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;
import java.util.Optional;

/**
 * The staff member's own access token, kept only as the details of the request's authentication so that a command
 * Waiter forwards to QR (closing a session, marking a table cleaned) can carry the delegation QR demands
 * ({@code X-Staff-Token}, PR #51). It lives exactly as long as the request: work outside a request, such as the
 * reconciler, has no token and so none is sent. It is a secret and never appears in logs or messages.
 */
public record StaffAccessToken(String value) {

    public StaffAccessToken {
        Objects.requireNonNull(value, "value");
    }

    /** The token of the staff request being served on this thread, if there is one. */
    public static Optional<String> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getDetails() instanceof StaffAccessToken token
                ? Optional.of(token.value()) : Optional.empty();
    }

    @Override
    public String toString() {
        return "StaffAccessToken[<redacted>]";
    }
}
