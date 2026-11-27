package com.restofy.auth.application.service;

import java.time.Clock;
import java.util.UUID;

import com.restofy.auth.application.service.StaffSessionService.PasswordChange;
import com.restofy.auth.application.service.revocation.RevocationPendingException;
import com.restofy.auth.domain.policy.PasswordResetPolicy;
import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.client.MailClient.Message;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database side of a self-service password change: one short transaction, with the notice sent after commit. */
@Service
// Session checks may revoke a stale session before refusing; that revocation must commit with the refusal.
@Transactional(noRollbackFor = {BadCredentialsException.class, RevocationPendingException.class})
public class PasswordChangeTransactions {
    private static final Logger LOG = LoggerFactory.getLogger(PasswordChangeTransactions.class);
    private final StaffSessionService sessions;
    private final MailClient mail;
    private final Clock clock;

    public PasswordChangeTransactions(StaffSessionService sessions, MailClient mail, Clock clock) {
        this.sessions = sessions;
        this.mail = mail;
        this.clock = clock;
    }

    public PasswordChange apply(Identity identity, long expectedVersion, String passwordHash, String locale) {
        PasswordChange change = sessions.changePassword(identity, expectedVersion, passwordHash);
        Message notice = new Message(UUID.randomUUID(), "PASSWORD_CHANGED", change.recipient(), locale, null, null,
                clock.instant().plus(PasswordResetPolicy.MAIL_DEADLINE));
        try {
            mail.afterCommit(notice);
        } catch (RuntimeException _) {
            // The notice is a courtesy; the change itself must not fail because the mail could not be queued.
            LOG.warn("password_event=DISPATCH_FAILED message_id={}", notice.messageId());
        }
        UUID user = identity.userId();
        boolean required = change.wasRequired();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                LOG.info("password_event=CHANGED user_id={} required={}", user, required);
            }
        });
        return change;
    }
}
