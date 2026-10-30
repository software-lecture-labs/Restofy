package com.restofy.auth.application.service;

import java.sql.Connection;
import java.util.UUID;

import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.security.MfaAttemptLimiter;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.connection-timeout=5000"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
class MfaAdmissionPoolIntegrationTests extends AuthSessionTestSupport {
    @Autowired private MfaAttemptLimiter limiter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource pool;

    @Test
    void peerAdmissionFinishesBeforeTheMfaTransactionBorrowsTheOnlyConnection() throws Exception {
        // Startup migration needs two connections; shrink only the running request pool.
        try (Connection retained = pool.getConnection(); Connection excess = pool.getConnection()) {
            assertThat(retained.isClosed()).isFalse();
            pool.setMinimumIdle(1);
            pool.setMaximumPoolSize(1);
            // Preserve one physical connection so jdbc:tc keeps this same disposable database alive.
            pool.evictConnection(excess);
        }
        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isEqualTo(1);
        Identity identity = new Identity(UUID.randomUUID(), UUID.randomUUID(), StaffRole.WAITER,
                SessionAuthState.AUTHENTICATED);
        assertThatThrownBy(() -> limiter.attempt(identity, "192.0.2.10", (state, session) -> true))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT sum(attempts) FROM auth_mfa_peer_limits", Integer.class)).isEqualTo(1);
    }
}
