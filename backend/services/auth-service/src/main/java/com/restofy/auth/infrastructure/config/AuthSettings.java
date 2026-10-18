package com.restofy.auth.infrastructure.config;

import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AuthSettings {
    private static final Logger LOG = LoggerFactory.getLogger(AuthSettings.class);
    private static final String SESSION_PREFIX = "auth.session.";
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "local", "test");
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
        try {
            restaurantId = UUID.fromString(required(environment, "restaurant-id"));
            var factory = KeyFactory.getInstance("RSA");
            privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(required(environment, "private-key"))));
            publicKey = (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(
                    privateKey.getModulus(), privateKey.getPublicExponent()));
            csrfSecret = Base64.getDecoder().decode(required(environment, "csrf-secret"));
            secure = strictBoolean(required(environment, "cookie-secure"));
            issuer = required(environment, "issuer");
            accessTtl = Long.parseLong(required(environment, "access-ttl-seconds"));
            refreshTtl = Long.parseLong(required(environment, "refresh-ttl-seconds"));
            origins = Arrays.stream(required(environment, "allowed-origins").split(",", -1)).map(String::strip).toList();
            boolean development = environment.getActiveProfiles().length > 0
                    && DEVELOPMENT_PROFILES.containsAll(List.of(environment.getActiveProfiles()));
            // MFA can only be switched off for local development; any other profile or APP_ENV keeps it mandatory.
            mfaRequired = strictBoolean(environment.getProperty(SESSION_PREFIX + "mfa-required", "true"));
            String appEnvironment = environment.getProperty("auth.bootstrap.environment", "");
            boolean developmentEnvironment = appEnvironment.isBlank() || DEVELOPMENT_PROFILES.contains(appEnvironment);
            if (!mfaRequired && (!development || !developmentEnvironment)) {
                throw new IllegalArgumentException();
            }
            if (!mfaRequired) {
                LOG.warn("ADMIN MFA is DISABLED (AUTH_MFA_REQUIRED=false). Development only; never enable outside local development.");
            }
            if (privateKey.getModulus().bitLength() < 2048 || csrfSecret.length < 32
                    || restaurantId.equals(new UUID(0, 0)) || issuer.length() > 100
                    || accessTtl != 900 || refreshTtl != 604800 || (!secure && !development)) {
                throw new IllegalArgumentException();
            }
            if (Arrays.stream(toUnsigned(csrfSecret)).distinct().count() < 16) {
                throw new IllegalArgumentException();
            }
            for (String origin : origins) {
                validateOrigin(origin, development);
            }
        } catch (Exception _) {
            throw new IllegalStateException("Invalid or missing Auth session configuration");
        }
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty(SESSION_PREFIX + suffix);
        if (value.isBlank()) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static boolean strictBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException();
        }
        return Boolean.parseBoolean(value);
    }

    private static int[] toUnsigned(byte[] bytes) {
        int[] values = new int[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            values[index] = Byte.toUnsignedInt(bytes[index]);
        }
        return values;
    }

    private static void validateOrigin(String origin, boolean development) {
        URI uri = URI.create(origin);
        boolean localHttp = development && "http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !localHttp) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().isEmpty() || origin.contains("*")) {
            throw new IllegalArgumentException();
        }
    }

    public UUID restaurantId() { return restaurantId; }
    public RSAPrivateCrtKey privateKey() { return privateKey; }
    public RSAPublicKey publicKey() { return publicKey; }
    public byte[] csrfSecret() { return csrfSecret.clone(); }
    public boolean secure() { return secure; }
    public boolean mfaRequired() { return mfaRequired; }
    public String issuer() { return issuer; }
    public long accessTtl() { return accessTtl; }
    public long refreshTtl() { return refreshTtl; }
    public List<String> origins() { return origins; }
}
