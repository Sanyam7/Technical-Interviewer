package com.techinterviewer.user;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An account. The email is stored in canonical form (see {@link EmailAddress#normalize}) in a case-insensitive
 * column. The account's role is fixed at creation: self-service registration can only ever create a
 * {@link Role#CANDIDATE}; an administrator is provisioned directly in the database.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "email", nullable = false, columnDefinition = "citext", length = EmailAddress.MAX_LENGTH)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        super();
    }

    private User(String email, String passwordHash, String displayName, Role role, Instant now) {
        this.email = Require.notBlank(email, "email", EmailAddress.MAX_LENGTH);
        this.passwordHash = Require.notBlank(passwordHash, "passwordHash", 100);
        this.displayName = Require.notBlank(displayName, "displayName", 80);
        this.role = Require.nonNull(role, "role");
        this.enabled = true;
        this.createdAt = Require.nonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * A new, enabled candidate account.
     *
     * @param email        canonical email (already normalised)
     * @param passwordHash encoded password, never the password itself
     */
    public static User candidate(String email, String passwordHash, String displayName, Instant now) {
        return new User(email, passwordHash, displayName, Role.CANDIDATE, now);
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
