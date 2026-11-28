package com.restofy.menu.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * How Menu verifies the staff credential Auth issues (the {@code staff-v1} contract). Everything here comes from
 * configuration, never from request data, and nothing here is a secret: the verification key is the PUBLIC half of
 * Auth's signing key.
 *
 * <ul>
 *   <li>{@code staffPublicKey} ({@code MENU_AUTH_STAFF_PUBLIC_KEY}): Base64 X.509 RSA public key matching Auth's
 *       {@code AUTH_JWT_PRIVATE_KEY}, at least 2048 bits. While it is blank staff authentication is OFF and every
 *       management request stays a 401: nothing is guessed, no key is invented and no key is bundled.</li>
 *   <li>{@code issuer} ({@code JWT_ISSUER}): must equal Auth's issuer; required while the key is set.</li>
 *   <li>{@code allowedOrigins} ({@code CORS_ALLOWED_ORIGINS}): the exact browser {@code Origin} allowlist of the
 *       mutation check. Empty means no browser mutation passes. An entry with a path, a wildcard, user info, a query
 *       or a fragment stops the application at startup.</li>
 * </ul>
 *
 * Error messages name the setting, never a value.
 */
@ConfigurationProperties("restofy.menu.auth")
public record StaffAuthProperties(String staffPublicKey, String issuer, List<String> allowedOrigins) {

    static final int MIN_RSA_BITS = 2048;

    public StaffAuthProperties {
        staffPublicKey = staffPublicKey == null ? "" : staffPublicKey.strip();
        issuer = issuer == null ? "" : issuer.strip();
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().map(String::strip)
                .filter(origin -> !origin.isEmpty()).distinct().toList();
        if (!allowedOrigins.stream().allMatch(StaffAuthProperties::validOrigin)) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS must contain explicit http(s) origins only");
        }
        if (!staffPublicKey.isEmpty()) {
            if (issuer.isEmpty()) {
                throw new IllegalStateException("JWT_ISSUER is required while MENU_AUTH_STAFF_PUBLIC_KEY is set");
            }
            decode(staffPublicKey);
        }
    }

    /** Staff authentication needs the verification key; without it no management request can authenticate. */
    public boolean staffAuthenticationEnabled() {
        return !staffPublicKey.isEmpty();
    }

    public RSAPublicKey rsaPublicKey() {
        return decode(staffPublicKey);
    }

    public Set<String> originSet() {
        return Set.copyOf(allowedOrigins);
    }

    private static RSAPublicKey decode(String encoded) {
        try {
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= MIN_RSA_BITS) {
                return rsa;
            }
        } catch (GeneralSecurityException | IllegalArgumentException _) {
            // Neither the key material nor the parser's message is reported.
        }
        throw new IllegalStateException("MENU_AUTH_STAFF_PUBLIC_KEY must be a Base64 X.509 RSA public key of at least "
                + MIN_RSA_BITS + " bits");
    }

    private static boolean validOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort();
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && "".equals(uri.getRawPath()) && !origin.contains("*") && (port == -1 || (port > 0 && port <= 65535));
        } catch (IllegalArgumentException _) {
            return false;
        }
    }
}
