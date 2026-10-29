package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.restofy.auth.infrastructure.config.SecretStrength;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetCodes {
    private static final int CODE_DIGITS = 6;
    private static final int CODE_SPACE = 1_000_000;
    /** 256-bit random value carried in the reset cookie; base64url encodes it to 43 characters. */
    private static final int TOKEN_BYTES = 32;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetCodes(Environment environment) {
        try {
            key = Base64.getDecoder().decode(environment.getRequiredProperty("auth.reset.hmac-key"));
            if (!SecretStrength.isStrong(key)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException _) {
            throw new IllegalStateException("Invalid or missing AUTH_RESET_HMAC_KEY (Base64, at least "
                    + SecretStrength.MIN_BYTES + " random bytes)");
        }
    }

    public String code() {
        return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", random.nextInt(CODE_SPACE));
    }

    public String token() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String digest(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException _) {
            throw new IllegalStateException("Password reset digest unavailable");
        }
    }

    public boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
