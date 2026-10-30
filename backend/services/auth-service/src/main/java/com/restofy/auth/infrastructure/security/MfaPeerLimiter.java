package com.restofy.auth.infrastructure.security;

import java.util.List;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore.Attempt;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore.Family;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MfaPeerLimiter {
    static final int ATTEMPTS_PER_WINDOW = 30;
    static final long WINDOW_SECONDS = 60;

    private final SharedRateLimitStore store;

    public MfaPeerLimiter(SharedRateLimitStore store) {
        this.store = store;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginLimitException.class)
    public void check(String peer) {
        store.check(Family.MFA_PEER, List.of(new Attempt(SharedRateLimitStore.peerKey(peer), WINDOW_SECONDS, ATTEMPTS_PER_WINDOW)));
    }
}
