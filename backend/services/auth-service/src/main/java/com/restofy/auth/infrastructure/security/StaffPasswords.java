package com.restofy.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class StaffPasswords {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    public void validateNewPassword(String password) {
        if (!validInput(password) || password.codePointCount(0, password.length()) < 12
                || password.isBlank()) {
            throw new IllegalArgumentException("Staff password must contain at least 12 characters and at most 72 UTF-8 bytes");
        }
    }

    public String hash(String password) {
        validateNewPassword(password);
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        boolean validPassword = validInput(password);
        boolean validHash = hash != null && hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}");
        // Unknown accounts and invalid inputs still perform a cost-12 comparison.
        boolean matched = encoder.matches(validPassword ? password : "invalid-credential", validHash ? hash : dummyHash);
        return validPassword && validHash && matched;
    }

    private boolean validInput(String password) {
        return password != null && !password.isEmpty() && password.length() <= 72
                && StandardCharsets.UTF_8.newEncoder().canEncode(password)
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
