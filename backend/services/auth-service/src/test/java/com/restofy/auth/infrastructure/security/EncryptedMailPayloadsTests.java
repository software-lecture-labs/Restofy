package com.restofy.auth.infrastructure.security;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class EncryptedMailPayloadsTests {
    private static final String PAYLOAD = "{\"recipient\":\"özel@example.test\",\"body\":\"Şifre 🔐\"}";

    @Test
    void unicodeRoundTripUsesRandomizedMessageBoundCiphertext() {
        EncryptedMailPayloads payloads = new EncryptedMailPayloads(key());
        UUID id = UUID.randomUUID();
        String first = payloads.encrypt(id, PAYLOAD);
        String second = payloads.encrypt(id, PAYLOAD);

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]+")
                .hasSizeLessThanOrEqualTo(EncryptedMailPayloads.MAX_ENVELOPE_CHARS);
        assertThat(payloads.decrypt(id, first)).isEqualTo(PAYLOAD);
        assertThat(payloads.decrypt(id, second)).isEqualTo(PAYLOAD);
        assertInvalid(() -> payloads.decrypt(UUID.randomUUID(), first));
        assertThat(first).doesNotContain(PAYLOAD);
        assertThat(payloads.toString()).doesNotContain(PAYLOAD, first);
    }

    @Test
    void constructorCopiesKeyAndOtherKeysCannotDecrypt() {
        byte[] key = key();
        EncryptedMailPayloads payloads = new EncryptedMailPayloads(key);
        EncryptedMailPayloads originalKey = new EncryptedMailPayloads(key.clone());
        Arrays.fill(key, (byte) 0);
        UUID id = UUID.randomUUID();
        String envelope = payloads.encrypt(id, PAYLOAD);

        assertThat(originalKey.decrypt(id, envelope)).isEqualTo(PAYLOAD);
        EncryptedMailPayloads wrongKey = new EncryptedMailPayloads(key());
        assertInvalid(() -> wrongKey.decrypt(id, envelope));
    }

    @Test
    void modifiedVersionNonceCiphertextAndTagAreRejected() {
        EncryptedMailPayloads payloads = new EncryptedMailPayloads(key());
        UUID id = UUID.randomUUID();
        byte[] envelope = Base64.getUrlDecoder().decode(payloads.encrypt(id, PAYLOAD));
        for (int position : new int[]{0, 1, 13, envelope.length - 1}) {
            byte[] changed = envelope.clone();
            changed[position] ^= 1;
            assertInvalid(() -> payloads.decrypt(id, encode(changed)));
        }
        assertInvalid(() -> payloads.decrypt(id, encode(Arrays.copyOf(envelope, 28))));
        assertInvalid(() -> payloads.decrypt(id, encode(Arrays.copyOf(envelope, envelope.length - 1))));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "invalid%%%", "a", "AA==", "private-secret-material"})
    void invalidEnvelopesUseTheSameSafeError(String envelope) {
        EncryptedMailPayloads payloads = new EncryptedMailPayloads(key());
        assertInvalid(() -> payloads.decrypt(UUID.randomUUID(), envelope));
    }

    @Test
    void enforcesUtf8AndEnvelopeBoundsAndRejectsNullInputs() {
        EncryptedMailPayloads payloads = new EncryptedMailPayloads(key());
        UUID id = UUID.randomUUID();
        String maximum = "ş".repeat(EncryptedMailPayloads.MAX_PLAINTEXT_BYTES / 2);
        assertThat(payloads.decrypt(id, payloads.encrypt(id, maximum))).isEqualTo(maximum);
        assertThat(payloads.decrypt(id, payloads.encrypt(id, ""))).isEmpty();
        assertInvalid(() -> payloads.encrypt(id, maximum + "a"));
        assertInvalid(() -> payloads.encrypt(id, "a".repeat(EncryptedMailPayloads.MAX_PLAINTEXT_BYTES + 1)));
        assertInvalid(() -> payloads.decrypt(id, "a".repeat(EncryptedMailPayloads.MAX_ENVELOPE_CHARS + 1)));
        assertInvalid(() -> payloads.decrypt(id, encode(new byte[17_000])));
        assertInvalid(() -> payloads.encrypt(null, PAYLOAD));
        assertInvalid(() -> payloads.encrypt(id, null));
        assertInvalid(() -> payloads.decrypt(null, payloads.encrypt(id, PAYLOAD)));
    }

    @Test
    void requiresExactlyThirtyTwoKeyBytes() {
        assertInvalid(() -> new EncryptedMailPayloads(null));
        for (int length : new int[]{0, 16, 24, 31, 33}) {
            assertInvalid(() -> new EncryptedMailPayloads(new byte[length]));
        }
    }

    private static void assertInvalid(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid encrypted mail payload").hasNoCause();
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
