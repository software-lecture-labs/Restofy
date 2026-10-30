package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import com.restofy.auth.infrastructure.config.MfaSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class MfaSecurityTests {
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    static MfaSettings settings(int offset) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) { bytes[index] = (byte) (index + offset); }
        return new MfaSettings(new MockEnvironment().withProperty("auth.mfa.encryption-key", Base64.getEncoder().encodeToString(bytes)));
    }

    @ParameterizedTest
    @CsvSource({"59,94287082", "1111111109,07081804", "1111111111,14050471", "1234567890,89005924",
            "2000000000,69279037", "20000000000,65353130"})
    void matchesRfc6238Sha1Vectors(long timestamp, String expected) {
        TotpVerifier verifier = verifier(timestamp);
        assertThat(verifier.code(RFC_SECRET, timestamp / 30, 8)).isEqualTo(expected);
        assertThat(verifier.code(RFC_SECRET, timestamp / 30, 6)).isEqualTo(expected.substring(2));
    }

    @Test
    void windowAndReplayAreBounded() {
        TotpVerifier verifier = verifier(3000);
        for (int delta = -2; delta <= 2; delta++) {
            long counter = 100L + delta;
            String code = verifier.code(RFC_SECRET, counter, 6);
            assertThat(verifier.verify(RFC_SECRET, code, null)).isEqualTo(Math.abs(delta) <= 1 ? counter : -1);
        }
        String accepted = verifier.code(RFC_SECRET, 100, 6);
        assertThat(verifier.verify(RFC_SECRET, accepted, 100L)).isEqualTo(-1);
        assertThat(verifier.verify(RFC_SECRET, accepted, 101L)).isEqualTo(-1);
        String next = verifier.code(RFC_SECRET, 101, 6);
        assertThat(verifier.verify(RFC_SECRET, next, 100L)).isEqualTo(101);
        assertThat(verifier(2999).verify(RFC_SECRET, accepted, null)).isEqualTo(100);
        assertThat(verifier(3030).verify(RFC_SECRET, accepted, null)).isEqualTo(100);
        assertThatThrownBy(() -> verifier.code(RFC_SECRET, 100, 7)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "1234567", "12a456", "１２３４５６", " 123456", "123456 "})
    void invalidOtpNeverMatches(String input) {
        assertThat(verifier(3000).verify(RFC_SECRET, input, null)).isEqualTo(-1);
    }

    @ParameterizedTest
    @CsvSource({"f,MY", "fo,MZXQ", "foo,MZXW6", "foob,MZXW6YQ", "fooba,MZXW6YTB", "foobar,MZXW6YTBOI"})
    void base32HasNoPadding(String input, String expected) {
        assertThat(TotpVerifier.base32(input.getBytes(StandardCharsets.US_ASCII))).isEqualTo(expected);
        assertThat(TotpVerifier.base32(new byte[0])).isEmpty();
    }

    @Test
    void encryptionUsesFreshNonceAndRejectsWrongKeyAadAndTampering() {
        MfaSecretCipher cipher = new MfaSecretCipher(settings(1));
        UUID user = UUID.randomUUID();
        byte[] encrypted = cipher.encrypt(user, RFC_SECRET);
        assertThat(encrypted).hasSize(48).isNotEqualTo(RFC_SECRET);
        assertThat(cipher.encrypt(user, RFC_SECRET)).isNotEqualTo(encrypted);
        assertThat(cipher.decrypt(user, encrypted)).isEqualTo(RFC_SECRET);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> cipher.decrypt(other, encrypted)).isInstanceOf(MfaUnavailableException.class);
        MfaSecretCipher wrong = new MfaSecretCipher(settings(2));
        assertThatThrownBy(() -> wrong.decrypt(user, encrypted)).isInstanceOf(MfaUnavailableException.class);
        encrypted[20] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(user, encrypted)).isInstanceOf(MfaUnavailableException.class);
        assertThatThrownBy(() -> cipher.decrypt(user, null)).isInstanceOf(MfaUnavailableException.class);
        assertThatThrownBy(() -> cipher.decrypt(user, new byte[1])).isInstanceOf(MfaUnavailableException.class);
        assertThatThrownBy(() -> cipher.encrypt(user, new byte[1])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingMalformedWeakAndWrongSizedKeysFailWithoutValues() {
        MockEnvironment environment = new MockEnvironment();
        assertThatThrownBy(() -> new MfaSettings(environment)).hasMessageContaining("Invalid or missing AUTH_MFA_ENCRYPTION_KEY");
        for (String value : new String[]{"invalid-key", Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(new byte[31])}) {
            environment.withProperty("auth.mfa.encryption-key", value);
            assertThatThrownBy(() -> new MfaSettings(environment)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid or missing AUTH_MFA_ENCRYPTION_KEY").hasNoCause();
        }
    }

    @Test
    void recoveryCodesAreRandomBoundToAccountAndNormalized() {
        MfaRecoveryCodes codes = new MfaRecoveryCodes();
        var generated = codes.generate();
        assertThat(generated).hasSize(10).doesNotHaveDuplicates().allMatch(value -> value.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){4}"));
        assertThat(codes.generate()).doesNotContainAnyElementsOf(generated);
        UUID user = UUID.randomUUID();
        String digest = codes.digest(user, generated.getFirst());
        assertThat(digest).hasSize(64).isNotEqualTo(generated.getFirst());
        String normalized = generated.getFirst().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        assertThat(codes.matches(digest, codes.digest(user, normalized))).isTrue();
        assertThat(codes.matches(digest, codes.digest(UUID.randomUUID(), generated.getFirst()))).isFalse();
        assertThat(codes.matches(digest, codes.digest(user, null))).isFalse();
        assertThat(codes.matches(digest, codes.digest(user, "invalid"))).isFalse();
        assertThat(new TotpVerifier(Clock.systemUTC()).newSecret()).hasSize(20).isNotEqualTo(RFC_SECRET);
    }

    private static TotpVerifier verifier(long seconds) {
        return new TotpVerifier(Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC));
    }
}
