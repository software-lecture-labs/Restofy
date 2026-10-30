package com.restofy.auth.support;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthTestDatabaseInitializerTests {
    @Test
    void wrapperRetainsServiceContextAndRejectsNonTestProfile() {
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            var initializer = new AuthTestDatabaseInitializer();
            assertThatThrownBy(() -> initializer.initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unsafe Auth test database configuration: only the test profile is allowed")
                    .hasNoCause();
        }
    }
}
