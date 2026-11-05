package com.restofy.auth.infrastructure.security;

import java.time.Instant;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.AuthSettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaRequirementSwitchTests {
    private static final String HASH = new StaffPasswords().hash("Mfa-switch-password-01");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private static UserSession session(StaffRole role, boolean mfaRequired) {
        return new UserSession(new StaffUser("staff@example.com", HASH, role), NOW, 604800, null, mfaRequired);
    }

    @Test
    void mfaIsRequiredByDefaultAndForAnExplicitTrue() {
        MockEnvironment environment = SessionSecurityTests.environment();
        assertThat(new AuthSettings(environment).mfaRequired()).isTrue();
        environment.withProperty("auth.session.mfa-required", "true");
        assertThat(new AuthSettings(environment).mfaRequired()).isTrue();
    }

    @Test
    void sessionStateFollowsTheSwitchAndOnlyAffectsAdmin() {
        assertThat(session(StaffRole.ADMIN, true).getAuthState()).isEqualTo(SessionAuthState.MFA_PENDING);
        assertThat(session(StaffRole.ADMIN, false).getAuthState()).isEqualTo(SessionAuthState.AUTHENTICATED);
        assertThat(session(StaffRole.WAITER, true).getAuthState()).isEqualTo(SessionAuthState.AUTHENTICATED);
        assertThat(session(StaffRole.KITCHEN, false).getAuthState()).isEqualTo(SessionAuthState.AUTHENTICATED);
        assertThat(session(StaffRole.ADMIN, false).getAbsoluteExpiresAt()).isEqualTo(NOW.plusSeconds(604800));
    }

    @Test
    void mfaCanBeDisabledOnlyInDevelopment() {
        MockEnvironment environment = SessionSecurityTests.environment();
        environment.withProperty("auth.session.mfa-required", "false");
        assertThat(new AuthSettings(environment).mfaRequired()).isFalse();
        environment.withProperty("auth.bootstrap.environment", "dev");
        assertThat(new AuthSettings(environment).mfaRequired()).isFalse();
    }

    @Test
    void disablingMfaOutsideDevelopmentFailsStartup() {
        MockEnvironment production = SessionSecurityTests.environment();
        production.setActiveProfiles("prod");
        production.withProperty("auth.session.cookie-secure", "true")
                .withProperty("auth.session.allowed-origins", "https://restofy.example")
                .withProperty("auth.session.mfa-required", "false");
        assertThatThrownBy(() -> new AuthSettings(production)).isInstanceOf(IllegalStateException.class);

        MockEnvironment productionEnvironment = SessionSecurityTests.environment();
        productionEnvironment.withProperty("auth.session.mfa-required", "false")
                .withProperty("auth.bootstrap.environment", "prod");
        assertThatThrownBy(() -> new AuthSettings(productionEnvironment)).isInstanceOf(IllegalStateException.class);

        MockEnvironment noProfile = SessionSecurityTests.environment();
        noProfile.setActiveProfiles();
        noProfile.withProperty("auth.session.mfa-required", "false");
        assertThatThrownBy(() -> new AuthSettings(noProfile)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anythingButTrueOrFalseFailsStartup() {
        MockEnvironment environment = SessionSecurityTests.environment();
        environment.withProperty("auth.session.mfa-required", "maybe");
        assertThatThrownBy(() -> new AuthSettings(environment)).isInstanceOf(IllegalStateException.class);
    }
}
