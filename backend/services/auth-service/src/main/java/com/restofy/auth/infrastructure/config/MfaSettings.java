package com.restofy.auth.infrastructure.config;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MfaSettings {
    private final SecretKey encryptionKey;

    public MfaSettings(Environment environment) {
        try {
            byte[] decoded = Base64.getDecoder().decode(environment.getRequiredProperty("auth.mfa.encryption-key"));
            if (decoded.length != 32 || java.util.stream.IntStream.range(0, decoded.length)
                    .map(index -> Byte.toUnsignedInt(decoded[index])).distinct().count() < 16) {
                throw new IllegalArgumentException();
            }
            encryptionKey = new SecretKeySpec(decoded, "AES");
            java.util.Arrays.fill(decoded, (byte) 0);
        } catch (RuntimeException _) {
            throw new IllegalStateException("Invalid or missing MFA encryption configuration");
        }
    }

    public SecretKey encryptionKey() { return encryptionKey; }
}
