package com.restofy.auth.application.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.persistence.repository.PasswordResetRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.PasswordResetCodes;
import com.restofy.auth.infrastructure.security.PasswordResetLimiter;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.infrastructure.security.TotpVerifier;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetIdentityRaceTests {
    @Test
    void emailChangedBetweenLookupAndIdentityLockCannotReceiveANewResetProof() {
        String oldEmail = "old-admin@example.com";
        UUID id = UUID.randomUUID();
        StaffUser user = mock(StaffUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getEmailNormalized()).thenReturn("new-admin@example.com");
        when(user.isActive()).thenReturn(true);
        when(user.getRole()).thenReturn(StaffRole.ADMIN);
        StaffUserRepository users = mock(StaffUserRepository.class);
        // The lookup completed before a management transaction changed the e-mail; the locked entity is now current.
        when(users.findByEmailNormalized(oldEmail)).thenReturn(Optional.of(user));
        when(users.lockIdentity(id)).thenReturn(Optional.of(id));
        when(users.findById(id)).thenReturn(Optional.of(user));
        StaffMfa state = mock(StaffMfa.class);
        when(state.enabled()).thenReturn(true);
        StaffMfaRepository mfa = mock(StaffMfaRepository.class);
        when(mfa.findByStaffUserId(id)).thenReturn(Optional.of(state));
        PasswordResetCodes codes = mock(PasswordResetCodes.class);
        when(codes.token()).thenReturn("neutral-cookie");
        PasswordResetRepository requests = mock(PasswordResetRepository.class);
        PasswordResetLimiter limiter = mock(PasswordResetLimiter.class);
        try (MailClient mail = mock(MailClient.class)) {
        PasswordResetService service = new PasswordResetService(users, mfa, requests, codes, limiter,
                mock(MfaFactors.class), mock(TotpVerifier.class), mock(StaffPasswords.class), mock(StaffSessionService.class),
                mail, mock(EntityManager.class), Clock.systemUTC());
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.request(oldEmail, "123456", "tr")).isEqualTo("neutral-cookie");
            verifyNoInteractions(requests, mail);
            verify(limiter).factorFailure(oldEmail);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        }
    }
}
