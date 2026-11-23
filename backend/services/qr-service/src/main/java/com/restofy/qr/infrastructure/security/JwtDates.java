package com.restofy.qr.infrastructure.security;

import java.time.Instant;

/** JJWT's typed setters and its injected clock require a Date. Keep that conversion at the library boundary. */
@SuppressWarnings("java:S2143")
public final class JwtDates {
    private JwtDates() { }

    public static java.util.Date from(Instant instant) {
        return java.util.Date.from(instant);
    }
}
