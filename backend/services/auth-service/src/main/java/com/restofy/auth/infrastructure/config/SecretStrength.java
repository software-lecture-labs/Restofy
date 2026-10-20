package com.restofy.auth.infrastructure.config;

import java.util.stream.IntStream;

/** Minimum quality every configured symmetric secret has to meet; rejects blank, short or repetitive material. */
public final class SecretStrength {
    /** 256 bits. */
    public static final int MIN_BYTES = 32;
    private static final int MIN_DISTINCT_BYTES = 16;

    private SecretStrength() {
    }

    public static boolean isStrong(byte[] secret) {
        return secret.length >= MIN_BYTES && hasVariety(secret);
    }

    /** True when at least {@value #MIN_DISTINCT_BYTES} different byte values occur, which fails all-zero or patterned keys. */
    public static boolean hasVariety(byte[] secret) {
        return IntStream.range(0, secret.length)
                .map(index -> Byte.toUnsignedInt(secret[index])).distinct().count() >= MIN_DISTINCT_BYTES;
    }
}
