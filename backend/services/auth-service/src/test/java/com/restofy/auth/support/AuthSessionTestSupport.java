package com.restofy.auth.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class AuthSessionTestSupport {
    @Autowired(required = false) private JdbcTemplate limitTestJdbc;

    @BeforeEach
    void resetPersistentAdmissionFixtures() {
        if (limitTestJdbc != null) {
            limitTestJdbc.update("DELETE FROM auth_login_limits");
            limitTestJdbc.update("DELETE FROM auth_mfa_peer_limits");
            limitTestJdbc.update("DELETE FROM password_reset_limits");
        }
    }
    private static final KeyPair KEYS = generateKeys();
    public static final KeyPair MAIL_KEYS = generateKeys();
    private static final byte[] OUTBOX_KEY = outboxKey();

    private static byte[] outboxKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void sessionProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.mail.private-key", () -> Base64.getEncoder().encodeToString(MAIL_KEYS.getPrivate().getEncoded()));
        registry.add("auth.mail.outbox-key", () -> Base64.getEncoder().encodeToString(OUTBOX_KEY));
        registry.add("auth.mail-outbox.relay-enabled", () -> "false");
        registry.add("auth.reset.hmac-key", () -> Base64.getEncoder().encodeToString(
                "0123456789abcdefghijklmnopqrstuv".getBytes(StandardCharsets.US_ASCII)));
        registry.add("auth.mfa.encryption-key", () -> Base64.getEncoder().encodeToString(
                "0123456789abcdefghijklmnopqrstuv".getBytes(StandardCharsets.US_ASCII)));
        registry.add("auth.session.restaurant-id", () -> "6c54daf0-9340-4a1c-981d-f9acc0261779");
        registry.add("auth.session.private-key", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        registry.add("auth.session.csrf-secret", () -> Base64.getEncoder().encodeToString(
                IntStream.range(1, 33).collect(ByteArrayOutputStream::new,
                        ByteArrayOutputStream::write, (left, right) -> left.writeBytes(right.toByteArray())).toByteArray()));
        registry.add("auth.session.cookie-secure", () -> "false");
        registry.add("auth.session.issuer", () -> "restofy-auth");
        registry.add("auth.session.access-ttl-seconds", () -> "900");
        registry.add("auth.session.refresh-ttl-seconds", () -> "604800");
        registry.add("auth.session.allowed-origins", () -> "http://localhost:5000");
    }
}
