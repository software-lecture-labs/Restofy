package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.restofy.auth.domain.entity.StaffEmail;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized void check(String email) {
        String account;
        try {
            account = StaffEmail.normalize(email);
        } catch (IllegalArgumentException _) {
            account = "invalid";
        }
        Instant now = clock.instant();
        windows.values().removeIf(window -> !now.isBefore(window.until));
        if (!windows.containsKey(account) && windows.size() >= 10000) {
            throw new LoginLimitException(900);
        }
        Window window = windows.computeIfAbsent(account, _ -> new Window(now.plusSeconds(900)));
        if (window.attempts >= 10) {
            throw new LoginLimitException(Math.max(1, window.until.getEpochSecond() - now.getEpochSecond()));
        }
        window.attempts++;
    }

    private static final class Window {
        private final Instant until;
        private int attempts;
        private Window(Instant until) { this.until = until; }
    }

    public static final class LoginLimitException extends RuntimeException {
        private final long retryAfter;
        public LoginLimitException(long retryAfter) {
            super("Login limit exceeded");
            this.retryAfter = retryAfter;
        }
        public long retryAfter() { return retryAfter; }
    }
}
