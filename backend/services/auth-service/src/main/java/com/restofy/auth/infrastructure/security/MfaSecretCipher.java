package com.restofy.auth.infrastructure.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import com.restofy.auth.infrastructure.config.MfaSettings;
import org.springframework.stereotype.Component;

/** Seals TOTP secrets with AES-256-GCM, binding each ciphertext to its owner through the associated data. */
@Component
public class MfaSecretCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int SEALED_BYTES = NONCE_BYTES + TotpVerifier.SECRET_BYTES + TAG_BITS / Byte.SIZE;
    private final MfaSettings settings;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(MfaSettings settings) {
        this.settings = settings;
    }

    public byte[] encrypt(UUID userId, byte[] plaintext) {
        if (plaintext.length != TotpVerifier.SECRET_BYTES) {
            throw new IllegalArgumentException("Invalid MFA secret length");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] encrypted = transform(Cipher.ENCRYPT_MODE, userId, nonce, plaintext);
        return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
    }

    public byte[] decrypt(UUID userId, byte[] encrypted) {
        if (encrypted == null || encrypted.length != SEALED_BYTES) {
            throw new MfaUnavailableException();
        }
        byte[] nonce = Arrays.copyOf(encrypted, NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encrypted, NONCE_BYTES, encrypted.length);
        return transform(Cipher.DECRYPT_MODE, userId, nonce, ciphertext);
    }

    private byte[] transform(int mode, UUID userId, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, settings.encryptionKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(("restofy:staff-mfa:" + userId).getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException _) {
            throw new MfaUnavailableException();
        }
    }
}
