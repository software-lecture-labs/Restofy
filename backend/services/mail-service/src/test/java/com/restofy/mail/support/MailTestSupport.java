package com.restofy.mail.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class MailTestSupport {
    public static final KeyPair KEYS = keys();
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }
    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("restofy.mail.public-key", () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        registry.add("restofy.mail.issuer", () -> "restofy-auth");
        registry.add("restofy.mail.from", () -> "sender@example.test");
        registry.add("restofy.mail.tls-mode", () -> "NONE");
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> "9");
        registry.add("spring.mail.username", () -> "");
        registry.add("spring.mail.password", () -> "");
    }
}
