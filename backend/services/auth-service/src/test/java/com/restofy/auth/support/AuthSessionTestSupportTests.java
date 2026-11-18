package com.restofy.auth.support;

import com.restofy.auth.infrastructure.config.AuthSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "auth.bootstrap.dev-users.enabled=false",
        "auth.bootstrap.initial-admin.enabled=false",
        "auth.session.mfa-required=false"
})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
class AuthSessionTestSupportTests extends AuthSessionTestSupport {
    @Autowired private AuthSettings settings;

    @Test
    void sessionTestsRequireMfaEvenWhenLocalConfigurationDisablesIt() {
        assertThat(settings.mfaRequired()).isTrue();
    }
}
