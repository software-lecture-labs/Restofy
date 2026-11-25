package com.restofy.menu.application.security;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The opaque guest credential of a customer, as QR issues it (43 URL-safe characters). It is a secret: it is only ever
 * handed to {@link GuestSessionVerifier}, it proves nothing by itself, and it never appears in a URL, a log line, an
 * error or an answer, which is why {@link #toString()} hides it and nothing else reads {@link #value()}.
 */
public record GuestToken(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    public GuestToken {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Malformed guest token.");
        }
    }

    /** The token for a well-formed value, empty for anything else (a malformed cookie is no credential). */
    public static Optional<GuestToken> parse(String raw) {
        return raw != null && FORMAT.matcher(raw).matches() ? Optional.of(new GuestToken(raw)) : Optional.empty();
    }

    @Override
    public String toString() {
        return "GuestToken[REDACTED]";
    }
}
