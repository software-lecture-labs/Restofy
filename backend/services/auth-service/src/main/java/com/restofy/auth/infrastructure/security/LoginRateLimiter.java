package com.restofy.auth.infrastructure.security;

import java.time.Clock;

import com.restofy.auth.domain.entity.StaffEmail;
import org.springframework.stereotype.Component;

/**
 * Throttles password logins.
 *
 * <p>The strict budget is kept per account <em>and</em> remote address, so someone who only knows an e-mail address
 * can exhaust their own budget but cannot lock the real owner out from another address. A much larger per-account
 * ceiling remains as a safety net against guessing spread over many addresses; reaching it is the accepted
 * availability trade-off.
 */
@Component
public class LoginRateLimiter {
    static final int ATTEMPTS_PER_ACCOUNT_AND_PEER = 10;
    static final int ATTEMPTS_PER_ACCOUNT = 50;
    static final long WINDOW_SECONDS = 900;
    private static final String INVALID_ACCOUNT = "invalid";

    private final BoundedWindows windows;

    public LoginRateLimiter(Clock clock) {
        this.windows = new BoundedWindows(clock);
    }

    public void check(String email, String peer) {
        String account = accountKey(email);
        rejectWhenLimited(windows.hit("peer:" + account + "|" + BoundedWindows.peerKey(peer),
                ATTEMPTS_PER_ACCOUNT_AND_PEER, WINDOW_SECONDS));
        rejectWhenLimited(windows.hit("account:" + account, ATTEMPTS_PER_ACCOUNT, WINDOW_SECONDS));
    }

    private static String accountKey(String email) {
        try {
            return StaffEmail.normalize(email);
        } catch (IllegalArgumentException _) {
            return INVALID_ACCOUNT;
        }
    }

    private static void rejectWhenLimited(long retryAfterSeconds) {
        if (retryAfterSeconds > 0) {
            throw new LoginLimitException(retryAfterSeconds);
        }
    }

    public static final class LoginLimitException extends RuntimeException {
        private final long retryAfter;

        public LoginLimitException(long retryAfter) {
            super("Login limit exceeded");
            this.retryAfter = retryAfter;
        }

        public long retryAfter() {
            return retryAfter;
        }
    }
}
