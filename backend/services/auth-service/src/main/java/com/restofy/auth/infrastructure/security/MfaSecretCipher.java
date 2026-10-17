package com.restofy.auth.infrastructure.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import com.restofy.auth.infrastructure.config.MfaSettings;
import org.springframework.stereotype.Component;

@Component
public class MfaSecretCipher {
    private final MfaSettings settings;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(MfaSettings settings) { this.settings = settings; }

    public byte[] encrypt(UUID userId, byte[] plaintext) {
        if (plaintext.length != 20) {
            throw new IllegalArgumentException("Invalid MFA secret length");
        }
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] encrypted = transform(Cipher.ENCRYPT_MODE, userId, nonce, plaintext);
        return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
    }

    public byte[] decrypt(UUID userId, byte[] encrypted) {
        if (encrypted == null || encrypted.length != 48) {
            throw new MfaUnavailableException();
        }
        byte[] nonce = java.util.Arrays.copyOf(encrypted, 12);
        byte[] ciphertext = java.util.Arrays.copyOfRange(encrypted, 12, encrypted.length);
        return transform(Cipher.DECRYPT_MODE, userId, nonce, ciphertext);
    }

    private byte[] transform(int mode, UUID userId, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, settings.encryptionKey(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("restofy:staff-mfa:" + userId).getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (java.security.GeneralSecurityException _) {
            throw new MfaUnavailableException();
        }
    }
}
