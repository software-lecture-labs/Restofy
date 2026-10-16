package com.restofy.auth.domain.entity;

import java.util.Locale;
import java.util.regex.Pattern;

public final class StaffEmail {
    private static final Pattern LOCAL_PART = Pattern.compile("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");
    private static final Pattern TOP_LEVEL_LABEL = Pattern.compile("[a-z]{2,}");

    private StaffEmail() {
    }

    // Domain labels are checked one by one instead of with a repeated regex group,
    // so validation cannot recurse deeply for long inputs.
    private static boolean hasValidFormat(String normalized, int at) {
        if (normalized.indexOf('@', at + 1) >= 0
                || !LOCAL_PART.matcher(normalized.substring(0, at)).matches()) {
            return false;
        }
        String[] labels = normalized.substring(at + 1).split("\\.", -1);
        if (labels.length < 2 || !TOP_LEVEL_LABEL.matcher(labels[labels.length - 1]).matches()) {
            return false;
        }
        for (int i = 0; i < labels.length - 1; i++) {
            if (!DOMAIN_LABEL.matcher(labels[i]).matches()) {
                return false;
            }
        }
        return true;
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
                || !hasValidFormat(normalized, at)) {
            throw new IllegalArgumentException("Invalid staff email");
        }
        return normalized;
    }
}
