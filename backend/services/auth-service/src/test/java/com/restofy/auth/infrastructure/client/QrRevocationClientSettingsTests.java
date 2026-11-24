package com.restofy.auth.infrastructure.client;

import java.time.Clock;
import java.util.Base64;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.infrastructure.security.QrRevocationReceipts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class QrRevocationClientSettingsTests {
    private static final Clock UTC = Clock.systemUTC();
    private MockEnvironment valid() {
        var environment=new MockEnvironment().withProperty("auth.q5.qr-url","https://qr.internal.example")
                .withProperty("auth.q5.qr-generation",AuthSessionTestSupport.Q5_GENERATION.toString())
                .withProperty("auth.q5.command-private-key",Base64.getEncoder().encodeToString(AuthSessionTestSupport.Q5_AUTH_KEYS.getPrivate().getEncoded()))
                .withProperty("auth.q5.command-public-key",Base64.getEncoder().encodeToString(AuthSessionTestSupport.Q5_AUTH_KEYS.getPublic().getEncoded()));
        environment.setActiveProfiles("prod");
        return environment;
    }

    @ParameterizedTest
    @ValueSource(strings={"auth.q5.qr-url","auth.q5.qr-generation","auth.q5.command-private-key","auth.q5.command-public-key"})
    void blankOrMalformedConfigurationFailsAtConstruction(String property) {
        for (String value:new String[]{"","invalid-secret-must-not-be-reported"}) {
            var environment=valid().withProperty(property,value);
            assertThatThrownBy(() -> new QrRevocationClient(environment,UTC))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Invalid Q5 command key, QR generation or internal QR URL").hasNoCause();
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"http://qr-service:8080","https://qr.example/path","https://user@qr.example","https://qr.example?secret=redacted","https://qr.example:0"})
    void unsafeInternalOriginsFailBeforeAnyHttp(String origin) {
        var environment=valid().withProperty("auth.q5.qr-url",origin);
        assertThatThrownBy(() -> new QrRevocationClient(environment,UTC)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mismatchedCommandPairFailsBeforeSendingAnyCommand() {
        var environment=valid().withProperty("auth.q5.command-public-key",
                Base64.getEncoder().encodeToString(AuthSessionTestSupport.Q5_QR_KEYS.getPublic().getEncoded()));
        assertThatThrownBy(() -> new QrRevocationClient(environment,UTC))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @Test
    void validatedClientDoesNotReparseMutableConfiguration() {
        var environment=valid();
        try (var client=new QrRevocationClient(environment,UTC)) {
            environment.withProperty("auth.q5.qr-generation","broken").withProperty("auth.q5.command-private-key","broken");
            assertThat(client.generation()).isEqualTo(AuthSessionTestSupport.Q5_GENERATION);
        }
    }

    @Test
    void missingOrBrokenReceiptVerificationKeyFailsAtStartup() {
        var missing=new MockEnvironment();
        assertThatThrownBy(() -> new QrRevocationReceipts(missing,UTC)).isInstanceOf(IllegalStateException.class);
        var broken=new MockEnvironment().withProperty("auth.q5.qr-public-key","broken-secret");
        assertThatThrownBy(() -> new QrRevocationReceipts(broken,UTC))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }
}
