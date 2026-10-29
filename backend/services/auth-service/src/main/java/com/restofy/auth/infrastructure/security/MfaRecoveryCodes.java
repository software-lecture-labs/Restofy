package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.domain.policy.MfaPolicy;
import org.springframework.stereotype.Component;

@Component
public class MfaRecoveryCodes {
    /** 13 random bytes give at least 20 base32 characters, i.e. 100 bits per code. */
    private static final int RANDOM_BYTES = 13;
    private static final int CODE_CHARACTERS = 20;
    private static final int GROUP_CHARACTERS = 4;
    private final SecureRandom random = new SecureRandom();

    public List<String> generate() {
        List<String> codes = new ArrayList<>();
        for (int index = 0; index < MfaPolicy.RECOVERY_CODE_COUNT; index++) {
            byte[] bytes = new byte[RANDOM_BYTES];
            random.nextBytes(bytes);
            codes.add(grouped(TotpVerifier.base32(bytes).substring(0, CODE_CHARACTERS)));
        }
        return List.copyOf(codes);
    }

    public String digest(UUID userId, String code) {
        String canonical = code == null ? "" : code.replace("-", "").toUpperCase(Locale.ROOT);
        if (!canonical.matches("[A-Z2-7]{" + CODE_CHARACTERS + "}")) {
            canonical = "invalid";
        }
        return StaffSessionService.digest("restofy:recovery:" + userId + ":" + canonical);
    }

    public boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String grouped(String encoded) {
        List<String> groups = new ArrayList<>();
        for (int start = 0; start < encoded.length(); start += GROUP_CHARACTERS) {
            groups.add(encoded.substring(start, start + GROUP_CHARACTERS));
        }
        return String.join("-", groups);
    }
}
