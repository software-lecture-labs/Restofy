package com.restofy.mail.infrastructure.config;

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
        try {
            key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(environment.getRequiredProperty("restofy.mail.public-key"))));
            issuer = environment.getRequiredProperty("restofy.mail.issuer");
            from = environment.getRequiredProperty("restofy.mail.from");
            String mode = environment.getRequiredProperty("restofy.mail.tls-mode");
            boolean development = EnvironmentPolicy.isDevelopment(environment);
            if (key.getModulus().bitLength() < MIN_KEY_BITS || issuer.isBlank() || issuer.length() > MAX_ISSUER_LENGTH
                    || !from.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
                    || !Set.of("STARTTLS", "SMTPS", "NONE").contains(mode)
                    || ("NONE".equals(mode) && (!development || !Set.of("localhost", "127.0.0.1").contains(
                            environment.getRequiredProperty("spring.mail.host"))))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception _) { throw new IllegalStateException("Invalid or missing Mail configuration"); }
    }
    public RSAPublicKey key() { return key; }
    public String issuer() { return issuer; }
    public String from() { return from; }
}
