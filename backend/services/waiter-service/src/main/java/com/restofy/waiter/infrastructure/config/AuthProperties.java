package com.restofy.waiter.infrastructure.config;

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
 * How Waiter verifies the browser credentials Auth and QR issue. They come only from configuration, never from
 * request data.
 *
 * <ul>
 *   <li>{@code staffPublicKey} is the Base64 X.509 RSA public key of Auth's staff JWTs
 *       ({@code WAITER_AUTH_STAFF_PUBLIC_KEY}). While it is blank staff authentication is off and every staff
 *       request stays a 401: nothing is guessed and no key is invented.</li>
 *   <li>{@code serviceUrl} ({@code AUTH_SERVICE_URL}) must then be an HTTPS origin, because the staff cookie is
 *       forwarded to Auth for the freshness check.</li>
 *   <li>{@code issuer} and {@code accessTtlSeconds} follow {@code JWT_ISSUER} and {@code JWT_ACCESS_TTL_SECONDS}
 *       of Auth; the lifetime is capped at Auth's 300 seconds.</li>
 *   <li>{@code allowedOrigins} ({@code CORS_ALLOWED_ORIGINS}) is the exact browser Origin allowlist of the
 *       Origin/CSRF checks. Empty means no browser mutation passes.</li>
 * </ul>
 */
@ConfigurationProperties("restofy.auth")
public record AuthProperties(String serviceUrl, String staffPublicKey, String issuer, long accessTtlSeconds,
                             List<String> allowedOrigins) {

    /** Auth's staff access token lifetime is 300 seconds; a longer configured value is never accepted. */
    public static final long MAX_ACCESS_TTL_SECONDS = 300;

    public AuthProperties {
        serviceUrl = serviceUrl == null ? "" : serviceUrl.strip();
        staffPublicKey = staffPublicKey == null ? "" : staffPublicKey.strip();
        issuer = issuer == null ? "" : issuer.strip();
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().map(String::strip)
                .filter(origin -> !origin.isEmpty()).distinct().toList();
        if (!allowedOrigins.stream().allMatch(AuthProperties::validOrigin)) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS must contain explicit http(s) origins only");
        }
        if (!staffPublicKey.isEmpty()) {
            if (issuer.isEmpty() || accessTtlSeconds <= 0 || accessTtlSeconds > MAX_ACCESS_TTL_SECONDS) {
                throw new IllegalStateException("The staff JWT issuer or lifetime is invalid");
            }
            publicKey(staffPublicKey);
            sessionUri(serviceUrl);
        }
    }

    /** Staff authentication needs the verification key; without it no staff request can authenticate. */
    public boolean staffAuthenticationEnabled() {
        return !staffPublicKey.isEmpty();
    }

    public Set<String> originSet() {
        return Set.copyOf(allowedOrigins);
    }

    public RSAPublicKey rsaPublicKey() {
        return publicKey(staffPublicKey);
    }

    /** Auth's session endpoint, derived from the configured HTTPS origin. */
    public URI sessionUri() {
        return sessionUri(serviceUrl);
    }

    private static URI sessionUri(String base) {
        try {
            URI uri = URI.create(base);
            int port = uri.getPort();
            String path = uri.getRawPath();
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && (port == -1 || (port > 0 && port <= 65535))
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path))) {
                return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + "/api/v1/auth/session");
            }
        } catch (IllegalArgumentException _) {
            // Configuration values never appear in startup errors.
        }
        throw new IllegalStateException("AUTH_SERVICE_URL must be an HTTPS origin while staff authentication is on");
    }

    private static RSAPublicKey publicKey(String encoded) {
        try {
            var key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= 2048) return rsa;
        } catch (GeneralSecurityException | IllegalArgumentException _) {
            // Neither key material nor the parsing error is reported.
        }
        throw new IllegalStateException(
                "WAITER_AUTH_STAFF_PUBLIC_KEY must be a Base64 X.509 RSA public key of at least 2048 bits");
    }

    private static boolean validOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && "".equals(uri.getRawPath()) && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535));
        } catch (IllegalArgumentException _) {
            return false;
        }
    }
}
