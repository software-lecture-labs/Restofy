package com.restofy.auth.infrastructure.security;

import java.util.function.BiFunction;

import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import org.springframework.stereotype.Service;

/** Commits the peer budget before borrowing the connection for the MFA account transaction. */
@Service
public class MfaAttemptLimiter {
    private final MfaPeerLimiter peers;
    private final MfaAccountAttempts accounts;

    public MfaAttemptLimiter(MfaPeerLimiter peers, MfaAccountAttempts accounts) {
        this.peers = peers;
        this.accounts = accounts;
    }

    public <T> T attempt(Identity identity, String peer, BiFunction<StaffMfa, UserSession, T> operation) {
        peers.check(peer);
        return accounts.attempt(identity, operation);
    }
}
