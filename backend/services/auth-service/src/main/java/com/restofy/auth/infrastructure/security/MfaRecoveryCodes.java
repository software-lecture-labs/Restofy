package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.restofy.auth.application.service.StaffSessionService;
import org.springframework.stereotype.Component;

@Component
public class MfaRecoveryCodes {
    private final SecureRandom random = new SecureRandom();

    public List<String> generate() {
        List<String> codes = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            byte[] bytes = new byte[13];
            random.nextBytes(bytes);
            String encoded = TotpVerifier.base32(bytes).substring(0, 20);
            codes.add(String.join("-", encoded.substring(0, 4), encoded.substring(4, 8),
                    encoded.substring(8, 12), encoded.substring(12, 16), encoded.substring(16, 20)));
        }
        return List.copyOf(codes);
    }

    public String digest(UUID userId, String code) {
        String canonical = code == null ? "" : code.replace("-", "").toUpperCase(Locale.ROOT);
        if (!canonical.matches("[A-Z2-7]{20}")) {
            canonical = "invalid";
        }
        return StaffSessionService.digest("restofy:recovery:" + userId + ":" + canonical);
    }

    public boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
