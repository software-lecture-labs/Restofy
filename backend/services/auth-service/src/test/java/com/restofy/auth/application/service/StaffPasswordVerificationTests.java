package com.restofy.auth.application.service;

import java.util.Optional;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaffPasswordVerificationTests {
    private static final String TEST_PASSWORD = "Test-only-password-01";
    private static StaffPasswords passwords;
    private static String hash;
    private StaffUserRepository users;
    private StaffPasswordVerification verification;

    @BeforeAll
    static void hashFixture() {
        passwords = new StaffPasswords();
        hash = passwords.hash(TEST_PASSWORD);
    }

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        users = mock(StaffUserRepository.class);
        verification = new StaffPasswordVerification(users, passwords);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(StaffRole.class)
    void verifiesPasswordAndStoredRoleWithoutAuthenticatingSession(StaffRole role) {
        StaffUser user = new StaffUser("staff@example.com", hash, role);
        when(users.findByEmailNormalized("staff@example.com")).thenReturn(Optional.of(user));
        var proof = verification.verify(" STAFF@EXAMPLE.COM ", TEST_PASSWORD);
        assertThat(proof.staffUserId()).isEqualTo(user.getId());
        assertThat(proof.role()).isEqualTo(role);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(users).findByEmailNormalized("staff@example.com");
    }

    @Test
    void usesSameFailureForWrongMissingAndInactiveAccounts() {
        StaffUser active = new StaffUser("active@example.com", hash, StaffRole.ADMIN);
        StaffUser inactive = new StaffUser("inactive@example.com", hash, StaffRole.WAITER);
        inactive.deactivate();
        when(users.findByEmailNormalized("active@example.com")).thenReturn(Optional.of(active));
        when(users.findByEmailNormalized("inactive@example.com")).thenReturn(Optional.of(inactive));
        when(users.findByEmailNormalized("missing@example.com")).thenReturn(Optional.empty());
        assertRejected("active@example.com", "wrong-password");
        assertRejected("inactive@example.com", TEST_PASSWORD);
        assertRejected("missing@example.com", TEST_PASSWORD);
        assertRejected("invalid", TEST_PASSWORD);
        assertRejected(null, null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void assertRejected(String email, String password) {
        assertThatThrownBy(() -> verification.verify(email, password))
                .isInstanceOf(BadCredentialsException.class).hasMessage("Invalid credentials");
    }
}
