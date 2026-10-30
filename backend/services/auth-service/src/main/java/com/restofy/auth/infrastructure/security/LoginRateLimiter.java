package com.restofy.auth.infrastructure.security;

import java.util.List;

import com.restofy.auth.domain.entity.StaffEmail;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore.Attempt;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore.Family;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private final SharedRateLimitStore store;

    public LoginRateLimiter(SharedRateLimitStore store) {
        this.store = store;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginLimitException.class)
    public void check(String email, String peer) {
        String account = accountKey(email);
        store.check(Family.LOGIN, List.of(
                new Attempt("peer:" + account + "|" + SharedRateLimitStore.peerKey(peer),
                        WINDOW_SECONDS, ATTEMPTS_PER_ACCOUNT_AND_PEER),
                new Attempt("account:" + account, WINDOW_SECONDS, ATTEMPTS_PER_ACCOUNT)));
    }

    private static String accountKey(String email) {
        try {
            return StaffEmail.normalize(email);
        } catch (IllegalArgumentException _) {
            return INVALID_ACCOUNT;
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
