package com.restofy.auth.domain.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.restofy.auth.domain.enums.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

@Entity
@Table(name = "staff_users")
@Getter
public class StaffUser {
    @Id
    private UUID id;
    @Column(name = "email_normalized", nullable = false, length = 254, unique = true)
    private String emailNormalized;
    @Column(name = "password_hash", nullable = false, length = 60)
    @JsonIgnore
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StaffRole role;
    @Column(name = "is_active", nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    private Instant passwordChangedAt;
    /** The password was set by an ADMIN; the member must choose their own before the session becomes usable. */
    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;
    @Version
    private long version;

    protected StaffUser() {
    }

    public StaffUser(String email, String passwordHash, StaffRole role) {
        this.id = UUID.randomUUID();
        this.emailNormalized = StaffEmail.normalize(email);
        if (passwordHash == null || !passwordHash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("Invalid staff password hash");
        }
        this.passwordHash = passwordHash;
        this.role = Objects.requireNonNull(role);
        this.active = true;
    }

    public void deactivate() {
        active = false;
    }

    /** A password the member chose (self-service change or e-mail reset). It clears any pending requirement. */
    public void changePassword(String hash, Instant now) {
        setPassword(hash, now);
        passwordChangeRequired = false;
    }

    /** A password an ADMIN chose: the member must replace it at the next sign-in. */
    public void assignTemporaryPassword(String hash, Instant now) {
        setPassword(hash, now);
        passwordChangeRequired = true;
    }

    /** A new account's initial password was chosen by an ADMIN, so it too must be replaced at the first sign-in. */
    public void requirePasswordChange() {
        passwordChangeRequired = true;
    }

    private void setPassword(String hash, Instant now) {
        if (hash == null || !hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("Invalid staff password hash");
        }
        passwordHash = hash;
        passwordChangedAt = Objects.requireNonNull(now);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
