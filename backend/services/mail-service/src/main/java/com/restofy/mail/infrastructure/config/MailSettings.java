package com.restofy.mail.infrastructure.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MailSettings {
    private static final int MIN_KEY_BITS = 2048;
    private static final int MAX_ISSUER_LENGTH = 100;
    private final RSAPublicKey key;
    private final String issuer;
    private final String from;

    public MailSettings(Environment environment) {
        key = read("MAIL_AUTH_PUBLIC_KEY", "a Base64 X.509 RSA public key of at least " + MIN_KEY_BITS + " bits", () -> {
            var value = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(environment.getRequiredProperty("restofy.mail.public-key"))));
            requireThat(value.getModulus().bitLength() >= MIN_KEY_BITS);
            return value;
        });
        issuer = read("JWT_ISSUER", "non-blank text of at most " + MAX_ISSUER_LENGTH + " characters", () -> {
            String value = environment.getRequiredProperty("restofy.mail.issuer");
            requireThat(!value.isBlank() && value.length() <= MAX_ISSUER_LENGTH);
            return value;
        });
        from = read("MAIL_FROM_ADDRESS", "an email address such as sender@example.com", () -> {
            String value = environment.getRequiredProperty("restofy.mail.from");
            requireThat(value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
            return value;
        });
        String mode = read("MAIL_SMTP_TLS_MODE", "STARTTLS or SMTPS (NONE only in local development)", () -> {
            String value = environment.getRequiredProperty("restofy.mail.tls-mode");
            requireThat(Set.of("STARTTLS", "SMTPS", "NONE").contains(value));
            requireThat(!"NONE".equals(value) || EnvironmentPolicy.isDevelopment(environment));
            return value;
        });
        if ("NONE".equals(mode)) {
            read("MAIL_SMTP_HOST", "localhost or 127.0.0.1 when MAIL_SMTP_TLS_MODE is NONE", () -> {
                String value = environment.getRequiredProperty("spring.mail.host");
                requireThat(Set.of("localhost", "127.0.0.1").contains(value));
                return value;
            });
        }
    }

    private static <T> T read(String variable, String expectation, Parser<T> parser) {
        try {
            return parser.parse();
        } catch (GeneralSecurityException | RuntimeException _) {
            throw new IllegalStateException("Invalid or missing " + variable + ": expected " + expectation);
        }
    }

    private static void requireThat(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException();
        }
    }
    public RSAPublicKey key() { return key; }
    public String issuer() { return issuer; }
    public String from() { return from; }

    @FunctionalInterface
    private interface Parser<T> {
        T parse() throws GeneralSecurityException;
    }
}
