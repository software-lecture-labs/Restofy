package com.restofy.mail.support;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailTestDatabaseInitializerTests {
    @Test
    void wrapperRetainsServiceContextAndRejectsNonTestProfile() {
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            assertThatThrownBy(() -> new MailTestDatabaseInitializer().initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unsafe Mail test database configuration: only the test profile is allowed")
                    .hasNoCause();
        }
    }
}
