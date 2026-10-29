package com.restofy.mail;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import com.restofy.mail.infrastructure.config.MailSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.assertj.core.api.Assertions.*;

class MailSettingsTests {
    private static final KeyPair KEYS = rsa(2048);

    @ParameterizedTest
    @CsvSource({"restofy.mail.public-key,MAIL_AUTH_PUBLIC_KEY", "restofy.mail.issuer,JWT_ISSUER",
            "restofy.mail.from,MAIL_FROM_ADDRESS", "restofy.mail.tls-mode,MAIL_SMTP_TLS_MODE",
            "spring.mail.host,MAIL_SMTP_HOST"})
    void missingSettingNamesItsEnvironmentVariable(String property, String variable) {
        MockEnvironment environment = valid();
        ((MockPropertySource) environment.getPropertySources().get("mockProperties")).getSource().remove(property);
        assertInvalid(environment, variable);
    }

    @Test
    void malformedAndWeakKeysAreRejectedWithoutExposingTheirValuesOrParserCause() {
        String malformed = "private-key-material-not-base64";
        assertInvalid(valid().withProperty("restofy.mail.public-key", malformed), "MAIL_AUTH_PUBLIC_KEY", malformed);
        String weak = Base64.getEncoder().encodeToString(rsa(1024).getPublic().getEncoded());
        assertInvalid(valid().withProperty("restofy.mail.public-key", weak), "MAIL_AUTH_PUBLIC_KEY", weak);
    }

    @Test
    void invalidIssuerAndSenderAreReportedSeparately() {
        assertInvalid(valid().withProperty("restofy.mail.issuer", " "), "JWT_ISSUER");
        String issuer = "sensitive-issuer-".repeat(10);
        assertInvalid(valid().withProperty("restofy.mail.issuer", issuer), "JWT_ISSUER", issuer);
        String from = "private-sender-invalid";
        assertInvalid(valid().withProperty("restofy.mail.from", from), "MAIL_FROM_ADDRESS", from);
    }

    @Test
    void invalidTlsModeAndNonLocalPlaintextHostAreReportedSeparately() {
        String mode = "private-invalid-mode";
        assertInvalid(valid().withProperty("restofy.mail.tls-mode", mode), "MAIL_SMTP_TLS_MODE", mode);
        String host = "private.smtp.example";
        assertInvalid(valid().withProperty("spring.mail.host", host), "MAIL_SMTP_HOST", host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev,prod", ""})
    void plaintextSmtpRequiresOnlyDevelopmentProfiles(String profiles) {
        MockEnvironment environment = valid();
        environment.setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(","));
        assertInvalid(environment, "MAIL_SMTP_TLS_MODE");
    }

    @ParameterizedTest
    @CsvSource({"dev,localhost", "local,127.0.0.1", "test,localhost"})
    void localDevelopmentPlaintextKeepsTheGetterContract(String profile, String host) {
        MockEnvironment environment = valid().withProperty("spring.mail.host", host);
        environment.setActiveProfiles(profile);
        MailSettings settings = new MailSettings(environment);
        assertThat(settings.key()).isEqualTo(KEYS.getPublic());
        assertThat(settings.issuer()).isEqualTo("restofy-auth");
        assertThat(settings.from()).isEqualTo("sender@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STARTTLS", "SMTPS"})
    void encryptedSmtpIsAllowedInProduction(String mode) {
        MockEnvironment environment = valid().withProperty("restofy.mail.tls-mode", mode)
                .withProperty("spring.mail.host", "smtp.example.test");
        environment.setActiveProfiles("prod");
        assertThatCode(() -> new MailSettings(environment)).doesNotThrowAnyException();
    }

    @Test
    void propertyResolutionExceptionDoesNotEscapeThroughMessageOrCause() {
        String secret = "private-property-resolution-error";
        MockEnvironment environment = new MockEnvironment() {
            @Override
            public String getRequiredProperty(String key) {
                throw new IllegalArgumentException(secret);
            }
        };
        assertInvalid(environment, "MAIL_AUTH_PUBLIC_KEY", secret);
    }

    private static void assertInvalid(MockEnvironment environment, String variable, String... forbidden) {
        Throwable failure = catchThrowable(() -> new MailSettings(environment));
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid or missing " + variable + ": expected ")
                .hasMessageNotContaining(Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()))
                .hasNoCause();
        for (String value : forbidden) {
            assertThat(failure).hasMessageNotContaining(value);
        }
    }

    private static MockEnvironment valid() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return environment.withProperty("restofy.mail.public-key", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()))
                .withProperty("restofy.mail.issuer", "restofy-auth").withProperty("restofy.mail.from", "sender@example.test")
                .withProperty("restofy.mail.tls-mode", "NONE").withProperty("spring.mail.host", "localhost");
    }

    private static KeyPair rsa(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot generate test key", exception);
        }
    }
}
