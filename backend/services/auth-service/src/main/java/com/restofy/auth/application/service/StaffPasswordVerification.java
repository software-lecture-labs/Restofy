package com.restofy.auth.application.service;

import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.domain.entity.StaffEmail;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffPasswordVerification {
    private final StaffUserRepository users;
    private final StaffPasswords passwords;

    public StaffPasswordVerification(StaffUserRepository users, StaffPasswords passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    /** Password proof only. This result is not an authenticated session or MFA proof. */
    @Transactional(readOnly = true)
    public PasswordProof verify(String email, String password) {
        Optional<StaffUser> user;
        try {
            user = users.findByEmailNormalized(StaffEmail.normalize(email));
        } catch (IllegalArgumentException exception) {
            user = Optional.empty();
        }
        boolean matched = passwords.matches(password, user.map(StaffUser::getPasswordHash).orElse(null));
        if (user.isEmpty() || !matched || !user.get().isActive()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        StaffUser verified = user.get();
        return new PasswordProof(verified.getId(), verified.getRole());
    }

    public record PasswordProof(UUID staffUserId, StaffRole role) {
    }
}
