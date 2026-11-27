package com.restofy.menu.application.ratelimit;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The only places a counter subject is made. Each is built from a verified id or from the TCP peer address of the
 * connection; none is built from a header, a parameter or a body, and every subject is short and of a fixed shape, so
 * the counter table cannot be filled with client-chosen text.
 */
public final class RateLimitSubjects {

    private static final Pattern PEER = Pattern.compile("[0-9A-Fa-f:.%]{1,64}");
    private static final String UNKNOWN_PEER = "unknown";

    private RateLimitSubjects() { }

    public static String restaurant(UUID restaurantId) {
        return "restaurant:" + restaurantId;
    }

    public static String actor(UUID restaurantId, UUID userId) {
        return "actor:" + restaurantId + ":" + userId;
    }

    /**
     * The address of the connection that reached Menu ({@code request.getRemoteAddr()}). Behind the Gateway that is the
     * Gateway's address: forwarding headers are not trusted (no trusted-proxy decision exists, SECURITY.md section 12), so
     * this key is deliberately coarse and only ever used for requests nobody could verify.
     */
    public static String peer(String remoteAddress) {
        return remoteAddress != null && PEER.matcher(remoteAddress).matches() ? "peer:" + remoteAddress : "peer:" + UNKNOWN_PEER;
    }
}
