package com.restofy.auth.infrastructure.config;

import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MfaSettings {
    private static final int AES_KEY_BYTES = 32;
    private final SecretKey encryptionKey;

    public MfaSettings(Environment environment) {
        try {
            byte[] decoded = Base64.getDecoder().decode(environment.getRequiredProperty("auth.mfa.encryption-key"));
            if (decoded.length != AES_KEY_BYTES || !SecretStrength.hasVariety(decoded)) {
                throw new IllegalArgumentException();
            }
            encryptionKey = new SecretKeySpec(decoded, "AES");
            Arrays.fill(decoded, (byte) 0);
        } catch (RuntimeException _) {
            throw new IllegalStateException("Invalid or missing AUTH_MFA_ENCRYPTION_KEY (Base64, exactly "
                    + AES_KEY_BYTES + " random bytes)");
        }
    }

    public SecretKey encryptionKey() {
        return encryptionKey;
    }
}
