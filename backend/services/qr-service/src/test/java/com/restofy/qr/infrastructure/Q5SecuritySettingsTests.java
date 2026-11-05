package com.restofy.qr.infrastructure;

import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import com.restofy.qr.support.Q5TestSecuritySupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class Q5SecuritySettingsTests {
    private MockEnvironment valid() {
        var environment=new MockEnvironment()
                .withProperty("qr.q5.auth-public-key",Q5TestSecuritySupport.encoded(Q5TestSecuritySupport.COMMAND.getPublic().getEncoded()))
                .withProperty("qr.q5.receipt-private-key",Q5TestSecuritySupport.encoded(Q5TestSecuritySupport.RECEIPT.getPrivate().getEncoded()))
                .withProperty("qr.q5.receipt-public-key",Q5TestSecuritySupport.encoded(Q5TestSecuritySupport.RECEIPT.getPublic().getEncoded()))
                .withProperty("qr.q5.staff-public-key",Q5TestSecuritySupport.encoded(Q5TestSecuritySupport.STAFF.getPublic().getEncoded()))
                .withProperty("qr.q5.restaurant-id","6c54daf0-9340-4a1c-981d-f9acc0261779")
                .withProperty("qr.q5.staff-issuer","restofy-auth").withProperty("qr.q5.access-ttl-seconds","900")
                .withProperty("qr.q5.allowed-origins","https://restofy.example").withProperty("APP_ENV","prod");
        environment.setActiveProfiles("prod");
        return environment;
    }

    @ParameterizedTest
    @ValueSource(strings={"qr.q5.auth-public-key","qr.q5.receipt-private-key","qr.q5.receipt-public-key","qr.q5.staff-public-key",
            "qr.q5.restaurant-id","qr.q5.staff-issuer","qr.q5.access-ttl-seconds","qr.q5.allowed-origins"})
    void blankCriticalConfigurationStopsContextBeforeReadiness(String property) {
        rejected(valid().withProperty(property,""));
    }

    @ParameterizedTest
    @ValueSource(strings={"qr.q5.auth-public-key","qr.q5.receipt-private-key","qr.q5.receipt-public-key","qr.q5.staff-public-key","qr.q5.restaurant-id","qr.q5.access-ttl-seconds"})
    void malformedCriticalConfigurationStopsContext(String property) {
        rejected(valid().withProperty(property,"invalid-secret-must-not-be-reported"));
    }

    @ParameterizedTest
    @ValueSource(strings={"*","https://*.example.com","https://restofy.example/path","https://user@restofy.example",
            "https://restofy.example?query=1","https://restofy.example,","http://restofy.example","http://localhost:5000"})
    void unsafeOriginsStopProductionContext(String origins) { rejected(valid().withProperty("qr.q5.allowed-origins",origins)); }

    @Test
    void mismatchedReceiptPairStopsContext() {
        rejected(valid().withProperty("qr.q5.receipt-public-key",Q5TestSecuritySupport.encoded(Q5TestSecuritySupport.COMMAND.getPublic().getEncoded())));
    }

    @Test
    void sharedStaffAndCommandKeysAreNotAcceptedAsSeparateTrust() {
        rejected(valid().withProperty("qr.q5.auth-public-key",Q5TestSecuritySupport.encoded(Q5TestSecuritySupport.STAFF.getPublic().getEncoded())));
    }

    @Test
    void localHttpRequiresBothDevelopmentSignalsAndSettingsAreImmutable() {
        var environment=valid().withProperty("qr.q5.allowed-origins","http://localhost:5000");
        environment.setActiveProfiles("dev");
        rejected(environment); // APP_ENV remains prod.
        environment.withProperty("APP_ENV","dev");
        var settings=new Q5SecuritySettings(environment);
        var key=settings.staffKey();
        environment.withProperty("qr.q5.staff-public-key","broken-after-startup");
        assertThat(settings.staffKey()).isSameAs(key);
        assertThat(settings.origins()).containsExactly("http://localhost:5000");
    }

    private static void rejected(MockEnvironment environment) {
        try (var context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(Q5SecuritySettings.class);
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Invalid Q5 RSA keys, restaurant, staff issuer, TTL or trusted origins");
            assertThat(context.isActive()).isFalse();
        }
    }
}
