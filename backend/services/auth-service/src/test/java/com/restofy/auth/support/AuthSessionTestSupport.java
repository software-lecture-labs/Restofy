package com.restofy.auth.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class AuthSessionTestSupport {
    private static final KeyPair KEYS = generateKeys();

    public static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void sessionProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.session.restaurant-id", () -> "6c54daf0-9340-4a1c-981d-f9acc0261779");
        registry.add("auth.session.private-key", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        registry.add("auth.session.csrf-secret", () -> Base64.getEncoder().encodeToString(
                java.util.stream.IntStream.range(1, 33).collect(java.io.ByteArrayOutputStream::new,
                        java.io.ByteArrayOutputStream::write, (left, right) -> left.writeBytes(right.toByteArray())).toByteArray()));
        registry.add("auth.session.cookie-secure", () -> "false");
        registry.add("auth.session.issuer", () -> "restofy-auth");
        registry.add("auth.session.access-ttl-seconds", () -> "900");
        registry.add("auth.session.refresh-ttl-seconds", () -> "604800");
        registry.add("auth.session.allowed-origins", () -> "http://localhost:5000");
    }
}
