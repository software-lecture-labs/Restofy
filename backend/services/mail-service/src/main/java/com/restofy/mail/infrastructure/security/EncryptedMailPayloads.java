package com.restofy.mail.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, message-bound encryption for short-lived Mail delivery payloads. */
public final class EncryptedMailPayloads {
    public static final int MAX_PLAINTEXT_BYTES = 16_384;
    public static final int MAX_ENVELOPE_CHARS = 24_000;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int PREFIX_BYTES = 1 + NONCE_BYTES;
    private static final int MIN_ENVELOPE_BYTES = PREFIX_BYTES + TAG_BITS / Byte.SIZE;
    private static final byte VERSION = 1;
    private static final String DOMAIN = "restofy:mail-delivery:v1";
    private static final String ERROR = "Invalid encrypted mail payload";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptedMailPayloads(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw invalid();
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
    }

    public String encrypt(UUID messageId, String json) {
        try {
            if (messageId == null || json == null || json.length() > MAX_PLAINTEXT_BYTES) {
                throw invalid();
            }
            byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
            if (plaintext.length > MAX_PLAINTEXT_BYTES) {
                throw invalid();
            }
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, messageId, nonce).doFinal(plaintext);
            byte[] envelope = new byte[PREFIX_BYTES + ciphertext.length];
            envelope[0] = VERSION;
            System.arraycopy(nonce, 0, envelope, 1, NONCE_BYTES);
            System.arraycopy(ciphertext, 0, envelope, PREFIX_BYTES, ciphertext.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException | RuntimeException _) {
            throw invalid();
        }
    }

    public String decrypt(UUID messageId, String envelope) {
        try {
            if (messageId == null || envelope == null || envelope.length() > MAX_ENVELOPE_CHARS
                    || !envelope.matches("[A-Za-z0-9_-]+")) {
                throw invalid();
            }
            byte[] bytes = Base64.getUrlDecoder().decode(envelope);
            if (bytes.length < MIN_ENVELOPE_BYTES || bytes.length > MIN_ENVELOPE_BYTES + MAX_PLAINTEXT_BYTES
                    || bytes[0] != VERSION) {
                throw invalid();
            }
            byte[] nonce = Arrays.copyOfRange(bytes, 1, PREFIX_BYTES);
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, messageId, nonce)
                    .doFinal(bytes, PREFIX_BYTES, bytes.length - PREFIX_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException _) {
            throw invalid();
        }
    }

    private Cipher cipher(int mode, UUID messageId, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD((DOMAIN + ":" + messageId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(ERROR);
    }
}
