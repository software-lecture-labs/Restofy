package com.restofy.auth.infrastructure.security;


import com.restofy.auth.domain.entity.StaffEmail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Throttles password logins.
 *
 * <p>The strict budget is kept per account <em>and</em> remote address, so someone who only knows an e-mail address
 * can exhaust their own budget but cannot lock the real owner out from another address. A much larger per-account
 * ceiling remains as a safety net against guessing spread over many addresses; reaching it is the accepted
 * availability trade-off.
 *
 * <p>Every attempt from one address, whatever the account, also counts against a per-address budget, checked first.
 * Without it one address could try any number of invented accounts and create two new buckets per account, enough
 * to fill the shared table (#60 review). Within that budget one address creates at most
 * {@code 2 * ATTEMPTS_PER_PEER + 1} buckets per window. The budget is generous because the staff of a restaurant sign
 * in from one shared address and successful logins count too. It is applied only when the client address is known
 * ({@link ClientAddressResolver#identifiesClients}); otherwise every client would share it and anyone could lock
 * every login without knowing a single e-mail address.
 */
@Component
public class LoginRateLimiter {
    static final int ATTEMPTS_PER_PEER = 100;
    static final int ATTEMPTS_PER_ACCOUNT_AND_PEER = 10;
    static final int ATTEMPTS_PER_ACCOUNT = 50;
    static final long WINDOW_SECONDS = 900;
    private static final String INVALID_ACCOUNT = "invalid";

    private final RateLimitStore store;
    private final boolean perPeerBudget;

    @Autowired
    public LoginRateLimiter(RateLimitStore store, ClientAddressResolver clients) {
        this(store, clients.identifiesClients());
    }

    LoginRateLimiter(RateLimitStore store, boolean perPeerBudget) {
        this.store = store;
        this.perPeerBudget = perPeerBudget;
    }

    public void check(String email, String peer) {
        String account = accountKey(email);
        String address = RateLimitStore.peerKey(peer);
        // Cheapest and widest first: an address over its budget creates no account bucket and burns no account budget.
        if (perPeerBudget) {
            rejectWhenLimited(store.hit("login-peer:" + address, ATTEMPTS_PER_PEER, WINDOW_SECONDS));
        }
        // The account+address budget goes next: a blocked address consumes nothing of the shared account ceiling.
        rejectWhenLimited(store.hit("login:" + account + "|" + address, ATTEMPTS_PER_ACCOUNT_AND_PEER, WINDOW_SECONDS));
        rejectWhenLimited(store.hit("login-account:" + account, ATTEMPTS_PER_ACCOUNT, WINDOW_SECONDS));
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
