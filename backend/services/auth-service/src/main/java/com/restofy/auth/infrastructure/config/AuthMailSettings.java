package com.restofy.auth.infrastructure.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import com.restofy.auth.infrastructure.security.EncryptedMailPayloads;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AuthMailSettings {
    private static final int MIN_RSA_BITS = 2048;
    private static final int AES_KEY_BYTES = 32;
    private final RSAPrivateCrtKey privateKey;
    private final byte[] outboxKey;
    private final String issuer;

    public AuthMailSettings(AuthSettings sessions, Environment environment) {
        issuer = sessions.issuer();
        try {
            privateKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(environment.getRequiredProperty("auth.mail.private-key"))));
            if (privateKey.getModulus().bitLength() < MIN_RSA_BITS
                    || privateKey.getModulus().equals(sessions.publicKey().getModulus())) {
                throw new IllegalArgumentException();
            }
        } catch (GeneralSecurityException | RuntimeException _) {
            throw new IllegalStateException("Invalid AUTH_MAIL_PRIVATE_KEY: expected a separate Base64 PKCS#8 RSA key, 2048+ bits");
        }
        try {
            outboxKey = Base64.getDecoder().decode(environment.getRequiredProperty("auth.mail.outbox-key"));
            if (outboxKey.length != AES_KEY_BYTES || !SecretStrength.isStrong(outboxKey)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException _) {
            throw new IllegalStateException("Invalid AUTH_MAIL_OUTBOX_KEY: expected Base64 of 32 varied random bytes");
        }
    }

    public RSAPrivateCrtKey privateKey() {
        return privateKey;
    }

    public String issuer() {
        return issuer;
    }

    @Bean
    EncryptedMailPayloads encryptedMailPayloads() {
        return new EncryptedMailPayloads(outboxKey);
    }
}
