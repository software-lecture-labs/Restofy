package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-window attempt counters kept in memory with a hard upper bound on the number of tracked keys.
 *
 * <p>When the bound is reached the oldest windows are dropped to make room instead of rejecting the newcomer.
 * Rejecting would let an attacker who floods the table with throw-away keys lock out every honest user for a whole
 * window; dropping the oldest window only forgets the attempts of the keys that were seen first.
 */
final class BoundedWindows {
    static final int MAX_TRACKED_KEYS = 10_000;
    static final int MAX_PEER_LENGTH = 64;
    private static final String UNKNOWN_PEER = "unknown";

    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Window> windows = new LinkedHashMap<>();

    BoundedWindows(Clock clock) {
        this(clock, MAX_TRACKED_KEYS);
    }

    BoundedWindows(Clock clock, int maxKeys) {
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    /** Normalises the remote address so that missing or oversized values share one bounded key. */
    static String peerKey(String peer) {
        return peer == null || peer.length() > MAX_PEER_LENGTH ? UNKNOWN_PEER : peer;
    }

    /**
     * Counts one attempt for {@code key}.
     *
     * @return {@code 0} when the attempt is admitted, otherwise the seconds until the key's window closes
     */
    synchronized long hit(String key, int limit, long windowSeconds) {
        Instant now = clock.instant();
        windows.values().removeIf(window -> !now.isBefore(window.until));
        if (!windows.containsKey(key)) {
            evictOldestWhileFull();
        }
        Window window = windows.computeIfAbsent(key, _ -> new Window(now.plusSeconds(windowSeconds)));
        if (window.attempts >= limit) {
            return Math.max(1, window.until.getEpochSecond() - now.getEpochSecond());
        }
        window.attempts++;
        return 0;
    }

    synchronized int trackedKeys() {
        return windows.size();
    }

    private void evictOldestWhileFull() {
        var iterator = windows.keySet().iterator();
        while (windows.size() >= maxKeys && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static final class Window {
        private final Instant until;
        private int attempts;

        private Window(Instant until) {
            this.until = until;
        }
    }
}
