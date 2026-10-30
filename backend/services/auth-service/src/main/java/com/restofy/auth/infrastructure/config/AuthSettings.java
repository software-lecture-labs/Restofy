package com.restofy.auth.infrastructure.config;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Session and token settings read from the environment.
 *
 * <p>Every failure names the offending environment variable (never its value) so a new team member can fix a
 * missing or malformed setting without guessing. Cross-field rules (development-only relaxations, key strength)
 * live here rather than in bean validation annotations because they depend on the active profiles.
 */
@Component
public class AuthSettings {
    private static final Logger LOG = LoggerFactory.getLogger(AuthSettings.class);
    private static final String SESSION_PREFIX = "auth.session.";
    private static final int MIN_RSA_BITS = 2048;
    private static final int MAX_ISSUER_LENGTH = 100;
    private static final Duration MIN_ACCESS_TTL = Duration.ofMinutes(5);
    private static final Duration MAX_ACCESS_TTL = Duration.ofHours(1);
    private static final Duration MIN_REFRESH_TTL = Duration.ofDays(1);
    private static final Duration MAX_REFRESH_TTL = Duration.ofDays(30);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final UUID restaurantId;
    private final RSAPrivateCrtKey privateKey;
    private final RSAPublicKey publicKey;
    private final byte[] csrfSecret;
    private final boolean secure;
    private final boolean mfaRequired;
    private final String issuer;
    private final long accessTtl;
    private final long refreshTtl;
    private final List<String> origins;

    public AuthSettings(Environment environment) {
        boolean development = EnvironmentPolicy.isDevelopment(environment);
        restaurantId = read("AUTH_RESTAURANT_ID", "a non-zero UUID", () -> {
            UUID id = UUID.fromString(required(environment, "restaurant-id"));
            requireThat(!id.equals(new UUID(0, 0)));
            return id;
        });
        privateKey = read("AUTH_JWT_PRIVATE_KEY", "a Base64 PKCS#8 RSA key of at least " + MIN_RSA_BITS + " bits", () -> {
            var key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(required(environment, "private-key"))));
            requireThat(key.getModulus().bitLength() >= MIN_RSA_BITS);
            return key;
        });
        publicKey = read("AUTH_JWT_PRIVATE_KEY", "a Base64 PKCS#8 RSA key", () -> (RSAPublicKey) KeyFactory
                .getInstance("RSA").generatePublic(new RSAPublicKeySpec(privateKey.getModulus(),
                        privateKey.getPublicExponent())));
        csrfSecret = read("AUTH_CSRF_SECRET", "Base64 of at least " + SecretStrength.MIN_BYTES + " varied random bytes",
                () -> {
                    byte[] secret = Base64.getDecoder().decode(required(environment, "csrf-secret"));
                    requireThat(SecretStrength.isStrong(secret));
                    return secret;
                });
        secure = read("AUTH_COOKIE_SECURE", "true, or false in development only", () -> {
            boolean value = strictBoolean(required(environment, "cookie-secure"));
            requireThat(value || development);
            return value;
        });
        issuer = read("JWT_ISSUER", "non-blank text of at most " + MAX_ISSUER_LENGTH + " characters", () -> {
            String value = required(environment, "issuer");
            requireThat(value.length() <= MAX_ISSUER_LENGTH);
            return value;
        });
        accessTtl = read("JWT_ACCESS_TTL_SECONDS", secondsBetween(MIN_ACCESS_TTL, MAX_ACCESS_TTL),
                () -> seconds(environment, "access-ttl-seconds", MIN_ACCESS_TTL, MAX_ACCESS_TTL));
        refreshTtl = read("JWT_REFRESH_TTL_SECONDS", secondsBetween(MIN_REFRESH_TTL, MAX_REFRESH_TTL),
                () -> seconds(environment, "refresh-ttl-seconds", MIN_REFRESH_TTL, MAX_REFRESH_TTL));
        origins = read("CORS_ALLOWED_ORIGINS", "comma separated https origins (http only for localhost in development)",
                () -> {
                    List<String> values = Arrays.stream(required(environment, "allowed-origins").split(",", -1))
                            .map(String::strip).toList();
                    values.forEach(origin -> validateOrigin(origin, development));
                    return values;
                });
        // MFA can only be switched off for local development; any other profile or APP_ENV keeps it mandatory.
        mfaRequired = read("AUTH_MFA_REQUIRED", "true, or false in development only", () -> {
            boolean value = strictBoolean(environment.getProperty(SESSION_PREFIX + "mfa-required", "true"));
            boolean developmentEnvironment = EnvironmentPolicy
                    .isDevelopmentEnvironmentName(environment.getProperty("auth.bootstrap.environment", ""));
            requireThat(value || (development && developmentEnvironment));
            return value;
        });
        if (!mfaRequired) {
            LOG.warn("ADMIN MFA is DISABLED (AUTH_MFA_REQUIRED=false). Development only; "
                    + "never enable outside local development.");
        }
    }

    /** Parses one setting; any failure is reported with the variable name and the expected shape, never the value. */
    private static <T> T read(String variable, String expectation, Parser<T> parser) {
        try {
            return parser.parse();
        } catch (GeneralSecurityException | RuntimeException _) {
            throw new IllegalStateException("Invalid or missing " + variable + ": expected " + expectation);
        }
    }

    private static String secondsBetween(Duration minimum, Duration maximum) {
        return "whole seconds from " + minimum.toSeconds() + " to " + maximum.toSeconds();
    }

    private static long seconds(Environment environment, String suffix, Duration minimum, Duration maximum) {
        long value = Long.parseLong(required(environment, suffix));
        requireThat(value >= minimum.toSeconds() && value <= maximum.toSeconds());
        return value;
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty(SESSION_PREFIX + suffix);
        requireThat(!value.isBlank());
        return value;
    }

    private static void requireThat(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException();
        }
    }

    private static boolean strictBoolean(String value) {
        requireThat("true".equals(value) || "false".equals(value));
        return Boolean.parseBoolean(value);
    }

    private static void validateOrigin(String origin, boolean development) {
        URI uri = URI.create(origin);
        boolean localHttp = development && "http".equals(uri.getScheme()) && LOCAL_HOSTS.contains(uri.getHost());
        requireThat((localHttp || "https".equals(uri.getScheme())) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && uri.getPath().isEmpty() && !origin.contains("*"));
    }

    public UUID restaurantId() {
        return restaurantId;
    }

    public RSAPrivateCrtKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public byte[] csrfSecret() {
        return csrfSecret.clone();
    }

    public boolean secure() {
        return secure;
    }

    public boolean mfaRequired() {
        return mfaRequired;
    }

    public String issuer() {
        return issuer;
    }

    public long accessTtl() {
        return accessTtl;
    }

    public long refreshTtl() {
        return refreshTtl;
    }

    public List<String> origins() {
        return origins;
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse() throws GeneralSecurityException;
    }
}
