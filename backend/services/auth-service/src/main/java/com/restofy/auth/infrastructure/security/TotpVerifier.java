package com.restofy.auth.infrastructure.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class TotpVerifier {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public TotpVerifier(Clock clock) { this.clock = clock; }

    public byte[] newSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        return secret;
    }

    public long verify(byte[] secret, String supplied, Long lastCounter) {
        boolean valid = supplied != null && supplied.matches("\\d{6}");
        byte[] candidate = (valid ? supplied : "------").getBytes(StandardCharsets.US_ASCII);
        long current = Math.floorDiv(clock.instant().getEpochSecond(), 30);
        long accepted = -1;
        for (long counter = current - 1; counter <= current + 1; counter++) {
            boolean matched = MessageDigest.isEqual(code(secret, counter, 6).getBytes(StandardCharsets.US_ASCII), candidate);
            if (matched && valid && counter >= 0 && (lastCounter == null || counter > lastCounter)) {
                accepted = counter;
            }
        }
        return accepted;
    }

    public String code(byte[] secret, long counter, int digits) {
        if (digits != 6 && digits != 8) {
            throw new IllegalArgumentException("Invalid TOTP digits");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = digest[digest.length - 1] & 15;
            int truncated = ByteBuffer.wrap(digest, offset, Integer.BYTES).getInt() & Integer.MAX_VALUE;
            int modulus = digits == 6 ? 1_000_000 : 100_000_000;
            return String.format(Locale.ROOT, digits == 6 ? "%06d" : "%08d", truncated % modulus);
        } catch (java.security.GeneralSecurityException _) {
            throw new MfaUnavailableException();
        }
    }

    public static String base32(byte[] input) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int available = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | Byte.toUnsignedInt(value);
            available += 8;
            while (available >= 5) {
                available -= 5;
                encoded.append(ALPHABET.charAt((buffer >>> available) & 31));
            }
        }
        if (available > 0) {
            encoded.append(ALPHABET.charAt((buffer << (5 - available)) & 31));
        }
        return encoded.toString();
    }
}
