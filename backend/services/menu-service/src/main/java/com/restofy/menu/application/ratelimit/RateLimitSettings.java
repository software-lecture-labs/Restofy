package com.restofy.menu.application.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * The limits, read from {@code restofy.menu.rate-limit.*} (application properties, not environment variables; Spring's
 * relaxed binding still lets an operator override one as {@code RESTOFY_MENU_RATELIMIT_PUBLICRESTAURANT_MAXREQUESTS}).
 * There is deliberately <b>no default in code and no off switch</b>: every policy must be present and sane or the
 * application does not start, so a missing or zero value can never become "unlimited". The shipped values live in
 * {@code application.properties} and are proposals pending the team's approval.
 *
 * <p>All windows are fixed windows aligned to the database clock, so every instance counts the same window.
 */
@ConfigurationProperties("restofy.menu.rate-limit")
public record RateLimitSettings(Policy publicRestaurant, Policy publicUnverified, Policy adminRead, Policy adminWrite,
        Policy adminRestaurantRead, Policy adminRestaurantWrite, Policy adminUnverified, Policy internalRestaurant,
        int failClosedRetryAfterSeconds) {

    private static final int MAX_FAIL_CLOSED_SECONDS = 300;

    public RateLimitSettings {
        require(publicRestaurant, "public-restaurant");
        require(publicUnverified, "public-unverified");
        require(adminRead, "admin-read");
        require(adminWrite, "admin-write");
        require(adminRestaurantRead, "admin-restaurant-read");
        require(adminRestaurantWrite, "admin-restaurant-write");
        require(adminUnverified, "admin-unverified");
        require(internalRestaurant, "internal-restaurant");
        if (failClosedRetryAfterSeconds < 1 || failClosedRetryAfterSeconds > MAX_FAIL_CLOSED_SECONDS) {
            throw new IllegalArgumentException("restofy.menu.rate-limit.fail-closed-retry-after-seconds must be between 1 and "
                    + MAX_FAIL_CLOSED_SECONDS + ".");
        }
    }

    /** At most {@code maxRequests} requests per {@code windowSeconds}. */
    public record Policy(int maxRequests, int windowSeconds) {

        static final int MAX_REQUESTS = 1_000_000;
        static final int MAX_WINDOW_SECONDS = 3600;

        public Policy {
            if (maxRequests < 1 || maxRequests > MAX_REQUESTS) {
                throw new IllegalArgumentException("A rate limit needs max-requests between 1 and " + MAX_REQUESTS + ".");
            }
            if (windowSeconds < 1 || windowSeconds > MAX_WINDOW_SECONDS) {
                throw new IllegalArgumentException("A rate limit needs window-seconds between 1 and " + MAX_WINDOW_SECONDS + ".");
            }
        }
    }

    public Policy policy(RateLimitScope scope) {
        return switch (scope) {
            case PUBLIC_RESTAURANT -> publicRestaurant;
            case PUBLIC_UNVERIFIED -> publicUnverified;
            case ADMIN_READ -> adminRead;
            case ADMIN_WRITE -> adminWrite;
            case ADMIN_RESTAURANT_READ -> adminRestaurantRead;
            case ADMIN_RESTAURANT_WRITE -> adminRestaurantWrite;
            case ADMIN_UNVERIFIED -> adminUnverified;
            case INTERNAL_RESTAURANT -> internalRestaurant;
        };
    }

    private static void require(Policy policy, String name) {
        Objects.requireNonNull(policy, "restofy.menu.rate-limit." + name + " is required (max-requests and window-seconds).");
    }
}
