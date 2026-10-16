package com.restofy.auth.domain.entity;

import java.util.Locale;
import java.util.regex.Pattern;

public final class StaffEmail {
    private static final Pattern FORMAT = Pattern.compile(
            "[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,}");

    private StaffEmail() {
    }

    public static String normalize(String email) {
        if (email == null || email.length() > 300) {
            throw new IllegalArgumentException("Invalid staff email");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (normalized.length() > 254 || at < 1 || at > 64
                || normalized.startsWith(".") || normalized.charAt(at - 1) == '.'
                || normalized.substring(0, at).contains("..")
                || !FORMAT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid staff email");
        }
        return normalized;
    }
}
