package com.restofy.auth.application.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.restofy.auth.domain.entity.StaffEmail;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.StaffBootstrapProperties;
import com.restofy.auth.infrastructure.config.StaffBootstrapProperties.Account;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffBootstrapService {
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "local", "test");
    private final StaffUserRepository users;
    private final StaffPasswords passwords;
    private final JdbcTemplate jdbc;

    public StaffBootstrapService(StaffUserRepository users, StaffPasswords passwords, JdbcTemplate jdbc) {
        this.users = users;
        this.passwords = passwords;
        this.jdbc = jdbc;
    }

    @Transactional
    public void initialize(StaffBootstrapProperties properties, String[] activeProfiles) {
        var dev = properties.getDevUsers();
        var initial = properties.getInitialAdmin();
        if (!dev.isEnabled() && !initial.isEnabled()) {
            return;
        }
        if (dev.isEnabled() && initial.isEnabled()) {
            throw new IllegalStateException("Development and initial ADMIN bootstrap must not run together");
        }
        if (dev.isEnabled()) {
            if (activeProfiles.length == 0 || !DEVELOPMENT_PROFILES.containsAll(List.of(activeProfiles))
                    || (!properties.getEnvironment().isBlank()
                    && !DEVELOPMENT_PROFILES.contains(properties.getEnvironment()))) {
                throw new IllegalStateException("Development staff bootstrap requires only dev/local/test profiles and environment");
            }
            List<Account> accounts = List.of(dev.getAdmin(), dev.getWaiter(), dev.getKitchen());
            Set<String> emails = new HashSet<>();
            for (Account account : accounts) {
                validate(account);
                if (!emails.add(StaffEmail.normalize(account.getEmail()))) {
                    throw new IllegalStateException("Development staff emails must be distinct");
                }
            }
        } else {
            validate(initial);
        }

        // Transaction-scoped, DB-local lock serializes concurrent application starts.
        // No lock survives commit/rollback. UNIQUE(email_normalized) is the final DB guard.
        jdbc.execute("SELECT pg_advisory_xact_lock(1381254745)");
        if (dev.isEnabled()) {
            createOrPreserve(dev.getAdmin(), StaffRole.ADMIN);
            createOrPreserve(dev.getWaiter(), StaffRole.WAITER);
            createOrPreserve(dev.getKitchen(), StaffRole.KITCHEN);
        } else {
            String email = StaffEmail.normalize(initial.getEmail());
            var existing = users.findByEmailNormalized(email);
            if (existing.isPresent()) {
                if (existing.get().getRole() != StaffRole.ADMIN) {
                    throw new IllegalStateException("Initial ADMIN email is already assigned to a different role");
                }
                return; // Preserve password, activation state and identity, including a disabled ADMIN.
            }
            if (users.existsByRole(StaffRole.ADMIN)) {
                throw new IllegalStateException("Initial ADMIN bootstrap cannot create an additional ADMIN");
            }
            createOrPreserve(initial, StaffRole.ADMIN);
        }
    }

    private void validate(Account account) {
        StaffEmail.normalize(account.getEmail());
        passwords.validateNewPassword(account.getPassword());
    }

    private void createOrPreserve(Account account, StaffRole role) {
        String email = StaffEmail.normalize(account.getEmail());
        var existing = users.findByEmailNormalized(email);
        if (existing.isPresent()) {
            if (existing.get().getRole() != role) {
                throw new IllegalStateException("Staff bootstrap email is already assigned to a different role");
            }
            return;
        }
        users.saveAndFlush(new StaffUser(email, passwords.hash(account.getPassword()), role));
    }
}
