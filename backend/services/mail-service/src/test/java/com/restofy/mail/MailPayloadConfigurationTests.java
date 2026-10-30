package com.restofy.mail;

import java.security.SecureRandom;
import java.util.Base64;

import com.restofy.mail.infrastructure.config.MailPayloadConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class MailPayloadConfigurationTests {
    @Test
    void missingMalformedWeakAndWrongLengthKeysFailWithoutLeakingTheConfiguredValue() {
        for (String value : new String[]{"private-invalid-value", "", Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(new byte[31])}) {
            var environment = new MockEnvironment().withProperty("restofy.mail.payload-key", value);
            assertThatThrownBy(() -> new MailPayloadConfiguration().encryptedMailPayloads(environment))
                    .isInstanceOf(IllegalStateException.class).hasNoCause()
                    .hasMessage("Invalid MAIL_PAYLOAD_ENCRYPTION_KEY: expected Base64 of 32 varied random bytes");
        }
        assertThatThrownBy(() -> new MailPayloadConfiguration().encryptedMailPayloads(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageContaining("MAIL_PAYLOAD_ENCRYPTION_KEY");
    }

    @Test
    void strongRandomKeyEnablesEncryption() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var environment = new MockEnvironment().withProperty("restofy.mail.payload-key", Base64.getEncoder().encodeToString(key));
        assertThat(new MailPayloadConfiguration().encryptedMailPayloads(environment)).isNotNull();
    }
}
