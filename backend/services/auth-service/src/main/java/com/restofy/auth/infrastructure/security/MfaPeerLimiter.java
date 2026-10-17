package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import org.springframework.stereotype.Component;

@Component
public class MfaPeerLimiter {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    public MfaPeerLimiter(Clock clock) { this.clock = clock; }

    public synchronized void check(String peer) {
        Instant now = clock.instant();
        windows.values().removeIf(window -> !now.isBefore(window.until));
        String key = peer == null || peer.length() > 64 ? "unknown" : peer;
        if (!windows.containsKey(key) && windows.size() >= 10000) { throw new LoginLimitException(60); }
        Window window = windows.computeIfAbsent(key, _ -> new Window(now.plusSeconds(60)));
        if (window.attempts >= 30) {
            throw new LoginLimitException(Math.max(1, window.until.getEpochSecond() - now.getEpochSecond()));
        }
        window.attempts++;
    }

    private static final class Window {
        private final Instant until;
        private int attempts;
        private Window(Instant until) { this.until = until; }
    }
}
