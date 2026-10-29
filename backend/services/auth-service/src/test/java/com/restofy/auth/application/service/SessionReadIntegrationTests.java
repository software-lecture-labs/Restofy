package com.restofy.auth.application.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.infrastructure.security.StaffJwtTokens;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SessionReadIntegrationTests extends AuthSessionTestSupport {
    private static final String PASSWORD = "Read-session-test-password-123";
    @Autowired private StaffSessionService service;
    @Autowired private StaffUserRepository users;
    @Autowired private StaffPasswords passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private StaffJwtTokens jwt;
    private StaffUser waiter;
    private StaffSessionService.Grant grant;

    @BeforeEach
    void setup() {
        clean();
        waiter = users.saveAndFlush(new StaffUser("reader@example.com", passwords.hash(PASSWORD), StaffRole.WAITER));
        grant = service.login(waiter.getEmailNormalized(), PASSWORD, null);
    }

    @AfterEach
    void clean() {
        for (String table : new String[]{"password_reset_requests", "mfa_recovery_codes", "staff_mfa",
                "refresh_tokens", "user_sessions", "staff_users"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void sessionGetDoesNotWaitForAnotherTransactionHoldingTheUserWriteLock() throws Exception {
        Cookie access = new Cookie(AuthCookies.ACCESS, grant.access());
        mvc.perform(get("/api/v1/auth/session").cookie(access)).andExpect(status().isOk());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                users.lockIdentity(waiter.getId()).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Read test did not release its fixture lock");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var read = executor.submit(() -> mvc.perform(get("/api/v1/auth/session").cookie(access))
                        .andExpect(status().isOk()).andReturn());
                assertThatCode(() -> read.get(3, TimeUnit.SECONDS)).doesNotThrowAnyException();
            } finally {
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "role", "revoked", "idle", "absolute", "state"})
    void readRejectsInvalidLiveStateWithoutPerformingRevocationWrites(String change) {
        var identity = jwt.verify(grant.access(), false);
        switch (change) {
            case "inactive" -> jdbc.update("UPDATE staff_users SET is_active=false WHERE id=?", waiter.getId());
            case "role" -> jdbc.update("UPDATE staff_users SET role='KITCHEN' WHERE id=?", waiter.getId());
            case "revoked" -> jdbc.update("UPDATE user_sessions SET revoked_at=now() WHERE id=?", grant.session().getId());
            case "idle" -> jdbc.update("""
                    UPDATE user_sessions SET created_at=now()-interval '2 days', last_activity_at=now()-interval '1 day'
                    WHERE id=?""", grant.session().getId());
            case "absolute" -> jdbc.update("""
                    UPDATE user_sessions SET created_at=now()-interval '2 days', last_activity_at=now()-interval '1 day',
                        absolute_expires_at=now()-interval '1 hour' WHERE id=?""", grant.session().getId());
            case "state" -> identity = new StaffJwtTokens.Identity(identity.userId(), identity.sessionId(),
                    identity.role(), SessionAuthState.MFA_PENDING);
            default -> throw new IllegalArgumentException("Unknown test state");
        }
        var checked = identity;
        assertThatThrownBy(() -> service.readSession(checked)).isInstanceOf(BadCredentialsException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Integer.class)).isZero();
        if (!"revoked".equals(change)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE revoked_at IS NOT NULL", Integer.class)).isZero();
        }
    }
}
