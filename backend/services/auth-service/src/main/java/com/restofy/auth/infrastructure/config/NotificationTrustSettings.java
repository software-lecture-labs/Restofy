package com.restofy.auth.infrastructure.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Only Notification's public verification key is installed here; its private key stays with Notification. */
@Component
public class NotificationTrustSettings {
    private static final int MIN_RSA_BITS = 2048;
    private final RSAPublicKey publicKey;

    public NotificationTrustSettings(Environment environment) {
        String encoded = environment.getProperty("auth.services.notification-public-key",
                environment.getProperty("AUTH_NOTIFICATION_PUBLIC_KEY", ""));
        if (encoded.isBlank()) {
            publicKey = null;
            return;
        }
        try {
            publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (publicKey.getModulus().bitLength() < MIN_RSA_BITS) {
                throw new IllegalArgumentException();
            }
        } catch (GeneralSecurityException | RuntimeException _) {
            throw new IllegalStateException("Invalid AUTH_NOTIFICATION_PUBLIC_KEY: expected Base64 X.509 RSA public key, 2048+ bits");
        }
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }
}
