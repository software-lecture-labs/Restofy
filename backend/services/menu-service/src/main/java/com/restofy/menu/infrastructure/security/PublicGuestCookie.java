package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.GuestToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Reads the guest credential QR issues to the browser ({@code restofy_guest}, ADR-QR-002 K3). Exactly one well-formed
 * cookie of that name counts; none, a malformed value or two cookies of the same name (which one the browser meant is
 * unknowable) mean "no credential". Nothing else is read: not a header, not a query or body field, not the staff
 * cookie. Whether a consumer service may read this cookie is still a question to QR (qr-request-01, decision 10);
 * until a real verifier is connected the value is never acted on.
 */
final class PublicGuestCookie {

    static final String NAME = "restofy_guest";

    private PublicGuestCookie() { }

    static Optional<GuestToken> single(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String value = null;
        int found = 0;
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                found++;
                value = cookie.getValue();
            }
        }
        return found == 1 ? GuestToken.parse(value) : Optional.empty();
    }
}
