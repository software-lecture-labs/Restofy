package com.restofy.qr.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Explicit ephemeral trust for full QR integration contexts; never packaged with the application. */
public abstract class Q5TestSecuritySupport {
    public static final KeyPair COMMAND = keys();
    public static final KeyPair RECEIPT = keys();
    public static final KeyPair STAFF = keys();

    @DynamicPropertySource
    static void q5Trust(DynamicPropertyRegistry registry) {
        registry.add("qr.q5.auth-public-key", () -> encoded(COMMAND.getPublic().getEncoded()));
        registry.add("qr.q5.receipt-private-key", () -> encoded(RECEIPT.getPrivate().getEncoded()));
        registry.add("qr.q5.receipt-public-key", () -> encoded(RECEIPT.getPublic().getEncoded()));
        registry.add("qr.q5.staff-public-key", () -> encoded(STAFF.getPublic().getEncoded()));
        registry.add("qr.q5.restaurant-id", () -> "6c54daf0-9340-4a1c-981d-f9acc0261779");
        registry.add("qr.q5.staff-issuer", () -> "restofy-auth");
        registry.add("qr.q5.access-ttl-seconds", () -> "900");
        registry.add("qr.q5.allowed-origins", () -> "https://restofy.example");
    }

    public static String encoded(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static KeyPair keys() {
        try { var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception unavailable) { throw new IllegalStateException("Test RSA unavailable"); }
    }
}
