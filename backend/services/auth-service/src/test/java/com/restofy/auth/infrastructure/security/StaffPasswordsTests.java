package com.restofy.auth.infrastructure.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StaffPasswordsTests {
    private static StaffPasswords passwords;
    private static final String TEST_PASSWORD = "Test-only-password-01";

    @BeforeAll
    static void setup() {
        passwords = new StaffPasswords();
    }

    @Test
    void hashesWithCost12AndRandomSalt() {
        String first = passwords.hash(TEST_PASSWORD);
        String second = passwords.hash(TEST_PASSWORD);
        assertThat(first).startsWith("$2a$12$").hasSize(60).isNotEqualTo(second).isNotEqualTo(TEST_PASSWORD);
        assertThat(passwords.matches(TEST_PASSWORD, first)).isTrue();
        assertThat(passwords.matches("wrong-password", first)).isFalse();
    }

    @Test
    void accepts72BytesButRejectsTruncationAndMultibyteOverflow() {
        String boundary = "a".repeat(72);
        String hash = passwords.hash(boundary);
        assertThat(passwords.matches(boundary, hash)).isTrue();
        assertThat(passwords.matches(boundary + "x", hash)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> passwords.hash(boundary + "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> passwords.hash("ü".repeat(37)));
        assertThat(passwords.matches("ü".repeat(37), hash)).isFalse();
        assertThat(passwords.matches("\uD800".repeat(12), hash)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "            "})
    void rejectsUnsafeNewPasswords(String password) {
        assertThatIllegalArgumentException().isThrownBy(() -> passwords.hash(password));
    }

    @Test
    void rejectsInvalidCredentialsAndHashesWithoutThrowing() {
        assertThat(passwords.matches(null, null)).isFalse();
        assertThat(passwords.matches("", "not-a-hash")).isFalse();
        assertThat(passwords.matches(TEST_PASSWORD, null)).isFalse();
        assertThat(passwords.matches(TEST_PASSWORD, "$2a$31$" + "a".repeat(53))).isFalse();
    }

    @Test
    void reportsCost12DurationWithoutCredentials() {
        long start = System.nanoTime();
        String hash = passwords.hash(TEST_PASSWORD);
        long hashed = System.nanoTime();
        assertThat(passwords.matches(TEST_PASSWORD, hash)).isTrue();
        long verified = System.nanoTime();
        System.out.printf("BCrypt cost=12: hash=%d ms, verify=%d ms (single test sample)%n",
                (hashed - start) / 1_000_000, (verified - hashed) / 1_000_000);
    }
}
