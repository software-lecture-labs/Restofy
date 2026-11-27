package com.restofy.auth.infrastructure.config;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Validates service-to-service base addresses read from the environment (ADR-ORTAK-001).
 *
 * <p>HTTPS is always accepted. Plain HTTP is accepted only inside a local network, when all of these hold:
 * {@code INTERNAL_HTTP_ALLOWED=true}; {@code APP_ENV} is {@code dev}, {@code local} or {@code test}; no
 * {@code prod}/{@code production} profile is active; and the host is a Compose service name or loopback. Turning the
 * setting on in production stops the service instead of being ignored, so a copied development {@code .env} is noticed.
 *
 * <p>Browser-facing origins and the Q5 revocation client are outside this rule; they keep their own checks.
 */
public final class InternalHttpPolicy {
    /** The environment variable, named in errors and in the start-up warning instead of any value. */
    public static final String SETTING = "INTERNAL_HTTP_ALLOWED";
    static final String ALLOWED_PROPERTY = "auth.internal-http-allowed";
    static final String ENVIRONMENT_PROPERTY = "auth.bootstrap.environment";

    private static final Logger LOG = LoggerFactory.getLogger(InternalHttpPolicy.class);
    private static final Set<String> PRODUCTION_NAMES = Set.of("prod", "production");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    /** A single Compose label. It must start with a letter, so numeric host forms that resolve to an IP never match. */
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z]([a-z0-9-]*[a-z0-9])?");

    private InternalHttpPolicy() {
    }

    /**
     * Returns {@code property} as a validated base address.
     *
     * @param name a short label for errors, for example {@code "Mail service"}; the address itself is never reported
     * @throws IllegalStateException when the address or the setting is not acceptable, so the service does not start
     */
    public static URI serviceAddress(Environment environment, String property, String name) {
        boolean allowed = allowed(environment);
        URI address;
        try {
            address = URI.create(environment.getRequiredProperty(property));
        } catch (IllegalArgumentException _) {
            throw new IllegalStateException("Invalid " + name + " address");
        }
        if (address.getHost() == null || address.getUserInfo() != null || address.getQuery() != null
                || address.getFragment() != null) {
            throw new IllegalStateException("Invalid " + name + " address");
        }
        if ("https".equals(address.getScheme())) {
            return address;
        }
        if (!"http".equals(address.getScheme()) || !allowed || !developmentEnvironment(environment)
                || !internalHost(address.getHost())) {
            throw new IllegalStateException("Invalid " + name + " address: HTTPS is required (plain HTTP only with "
                    + SETTING + "=true, APP_ENV dev/local/test and a Compose service name or loopback host)");
        }
        LOG.warn("internal_http=ENABLED setting={} address={} traffic to this service is plain text on the internal "
                + "network", SETTING, origin(address));
        return address;
    }

    /**
     * Reads the setting strictly. Only {@code true} and {@code false} are valid, and {@code true} in a production
     * environment is refused rather than silently ignored.
     */
    static boolean allowed(Environment environment) {
        String value = environment.getProperty(ALLOWED_PROPERTY, "false");
        boolean allowed = switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(SETTING + " must be true or false");
        };
        if (allowed && production(environment)) {
            throw new IllegalStateException(SETTING + "=true is not allowed when APP_ENV or a profile is production");
        }
        return allowed;
    }

    private static boolean production(Environment environment) {
        String name = environment.getProperty(ENVIRONMENT_PROPERTY, "").strip().toLowerCase(Locale.ROOT);
        return PRODUCTION_NAMES.contains(name) || Arrays.stream(EnvironmentPolicy.activeProfiles(environment))
                .map(profile -> profile.toLowerCase(Locale.ROOT)).anyMatch(PRODUCTION_NAMES::contains);
    }

    /** Unlike the MFA rule, an unset APP_ENV does not count: the exception needs a named development environment. */
    private static boolean developmentEnvironment(Environment environment) {
        String name = environment.getProperty(ENVIRONMENT_PROPERTY, "");
        return !name.isBlank() && EnvironmentPolicy.isDevelopmentEnvironmentName(name) && !production(environment);
    }

    private static boolean internalHost(String host) {
        return LOOPBACK_HOSTS.contains(host) || SERVICE_NAME.matcher(host).matches();
    }

    private static String origin(URI address) {
        return address.getScheme() + "://" + address.getHost() + (address.getPort() < 0 ? "" : ":" + address.getPort());
    }
}
