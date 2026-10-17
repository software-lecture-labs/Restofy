package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetCodes {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetCodes(Environment environment) {
        try {
            key = Base64.getDecoder().decode(environment.getRequiredProperty("auth.reset.hmac-key"));
            if (key.length < 32 || java.util.stream.IntStream.range(0, key.length)
                    .map(index -> Byte.toUnsignedInt(key[index])).distinct().count() < 16) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException _) {
            throw new IllegalStateException("Invalid or missing password reset configuration");
        }
    }

    public String code() { return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }

    public String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String digest(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException _) {
            throw new IllegalStateException("Password reset digest unavailable");
        }
    }

    public boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
