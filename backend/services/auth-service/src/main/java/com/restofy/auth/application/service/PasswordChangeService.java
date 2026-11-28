package com.restofy.auth.application.service;

import com.restofy.auth.application.service.PasswordChangeRefusedException.Reason;
import com.restofy.auth.application.service.StaffSessionService.PasswordChange;
import com.restofy.auth.infrastructure.security.PasswordChangeLimiter;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

/**
 * A signed-in member replaces their own password: every role, from a usable session or from a
 * {@code PASSWORD_PENDING} one. Deliberately not transactional: both BCrypt checks and the new hash run before the
 * transaction, so no lock or connection is held while they work.
 */
@Service
public class PasswordChangeService {
    private final PasswordChangeLimiter limiter;
    private final StaffPasswordVerification verification;
    private final StaffPasswords passwords;
    private final PasswordChangeTransactions transactions;

    public PasswordChangeService(PasswordChangeLimiter limiter, StaffPasswordVerification verification,
            StaffPasswords passwords, PasswordChangeTransactions transactions) {
        this.limiter = limiter;
        this.verification = verification;
        this.passwords = passwords;
        this.transactions = transactions;
    }

    public PasswordChange change(Identity identity, String currentPassword, String newPassword, String locale) {
        limiter.check(identity.sessionId());
        StaffPasswordVerification.PasswordProof proof;
        try {
            proof = verification.verifyAccount(identity.userId(), currentPassword);
        } catch (BadCredentialsException _) {
            throw new PasswordChangeRefusedException(Reason.CURRENT_PASSWORD_REJECTED);
        }
        try {
            passwords.validateNewPassword(newPassword);
        } catch (IllegalArgumentException _) {
            throw new PasswordChangeRefusedException(Reason.WEAK_PASSWORD);
        }
        // The current password has just matched, so plain equality is the same test as a hash comparison.
        if (newPassword.equals(currentPassword)) {
            throw new PasswordChangeRefusedException(Reason.SAME_PASSWORD);
        }
        return transactions.apply(identity, proof.version(), passwords.hash(newPassword), locale);
    }
}
