package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class StaffPasswords {
    static final int BCRYPT_COST = 12;
    static final int MIN_PASSWORD_LENGTH = 12;
    /** BCrypt silently ignores everything after the 72nd byte, so longer input is refused instead. */
    static final int MAX_PASSWORD_BYTES = 72;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    public void validateNewPassword(String password) {
        if (!validInput(password) || password.codePointCount(0, password.length()) < MIN_PASSWORD_LENGTH
                || password.isBlank()) {
            throw new IllegalArgumentException("Staff password must contain at least " + MIN_PASSWORD_LENGTH
                    + " characters and at most " + MAX_PASSWORD_BYTES + " UTF-8 bytes");
        }
    }

    public String hash(String password) {
        validateNewPassword(password);
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        boolean validPassword = validInput(password);
        boolean validHash = hash != null && hash.matches("\\$2[aby]\\$" + BCRYPT_COST + "\\$[./A-Za-z0-9]{53}");
        // Unknown accounts and invalid inputs still pay the full BCrypt cost.
        boolean matched = encoder.matches(validPassword ? password : "invalid-credential", validHash ? hash : dummyHash);
        return validPassword && validHash && matched;
    }

    private boolean validInput(String password) {
        return password != null && !password.isEmpty() && password.length() <= MAX_PASSWORD_BYTES
                && StandardCharsets.UTF_8.newEncoder().canEncode(password)
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}
