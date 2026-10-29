package com.restofy.auth.infrastructure.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/** RFC 6238 time-based one-time passwords (HMAC-SHA1, 30 second steps) plus the RFC 4648 base32 encoding. */
@Component
public class TotpVerifier {
    /** 160-bit shared secret, the size RFC 4226 recommends for HMAC-SHA1. */
    public static final int SECRET_BYTES = 20;
    /** Code used to run the comparison when the supplied value is malformed, so timing does not reveal that. */
    public static final String NO_CODE = "------";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final long TIME_STEP_SECONDS = 30;
    private static final int ACCEPTED_STEP_SKEW = 1;
    private static final int CODE_DIGITS = 6;
    private static final int LONG_CODE_DIGITS = 8;
    private static final int CODE_MODULUS = 1_000_000;
    private static final int LONG_CODE_MODULUS = 100_000_000;
    private static final int DYNAMIC_TRUNCATION_MASK = 0x0F;
    private static final int BITS_PER_BASE32_CHAR = 5;
    private static final int BASE32_MASK = (1 << BITS_PER_BASE32_CHAR) - 1;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public TotpVerifier(Clock clock) {
        this.clock = clock;
    }

    public byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    public long verify(byte[] secret, String supplied, Long lastCounter) {
        boolean valid = supplied != null && supplied.matches("\\d{" + CODE_DIGITS + "}");
        byte[] candidate = (valid ? supplied : NO_CODE).getBytes(StandardCharsets.US_ASCII);
        long current = Math.floorDiv(clock.instant().getEpochSecond(), TIME_STEP_SECONDS);
        long accepted = -1;
        for (long counter = current - ACCEPTED_STEP_SKEW; counter <= current + ACCEPTED_STEP_SKEW; counter++) {
            boolean matched = MessageDigest.isEqual(
                    code(secret, counter, CODE_DIGITS).getBytes(StandardCharsets.US_ASCII), candidate);
            if (matched && valid && counter >= 0 && (lastCounter == null || counter > lastCounter)) {
                accepted = counter;
            }
        }
        return accepted;
    }

    public String code(byte[] secret, long counter, int digits) {
        if (digits != CODE_DIGITS && digits != LONG_CODE_DIGITS) {
            throw new IllegalArgumentException("Invalid TOTP digits");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = digest[digest.length - 1] & DYNAMIC_TRUNCATION_MASK;
            int truncated = ByteBuffer.wrap(digest, offset, Integer.BYTES).getInt() & Integer.MAX_VALUE;
            boolean shortCode = digits == CODE_DIGITS;
            int modulus = shortCode ? CODE_MODULUS : LONG_CODE_MODULUS;
            return String.format(Locale.ROOT, shortCode ? "%06d" : "%08d", truncated % modulus);
        } catch (GeneralSecurityException _) {
            throw new MfaUnavailableException();
        }
    }

    public static String base32(byte[] input) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int available = 0;
        for (byte value : input) {
            buffer = (buffer << Byte.SIZE) | Byte.toUnsignedInt(value);
            available += Byte.SIZE;
            while (available >= BITS_PER_BASE32_CHAR) {
                available -= BITS_PER_BASE32_CHAR;
                encoded.append(ALPHABET.charAt((buffer >>> available) & BASE32_MASK));
            }
        }
        if (available > 0) {
            encoded.append(ALPHABET.charAt((buffer << (BITS_PER_BASE32_CHAR - available)) & BASE32_MASK));
        }
        return encoded.toString();
    }
}
