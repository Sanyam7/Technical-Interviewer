package com.techinterviewer.security;

import com.techinterviewer.common.persistence.BaseEntity;
import com.techinterviewer.common.validation.Require;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One refresh token of a rotation chain. Only the SHA-256 hex digest of the secret value is stored. All tokens
 * obtained from one login share a {@code familyId}; using a token replaces it by the next one of its family
 * ({@link #rotateTo}), and presenting a token that was already replaced is how theft is detected.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @Column(name = "replaced_by")
    private @Nullable UUID replacedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
        super();
    }

    RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant expiresAt, Instant now) {
        this.userId = Require.nonNull(userId, "userId");
        this.familyId = Require.nonNull(familyId, "familyId");
        this.tokenHash = Require.nonNull(tokenHash, "tokenHash");
        if (!tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tokenHash must be 64 lower-case hex characters");
        }
        this.expiresAt = Require.nonNull(expiresAt, "expiresAt");
        this.createdAt = Require.nonNull(now, "now");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be after the creation time");
        }
    }

    UUID getUserId() {
        return userId;
    }

    UUID getFamilyId() {
        return familyId;
    }

    String getTokenHash() {
        return tokenHash;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    @Nullable Instant getRevokedAt() {
        return revokedAt;
    }

    @Nullable UUID getReplacedBy() {
        return replacedBy;
    }

    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** A rotated token is revoked too: rotating sets both the revocation time and the successor. */
    boolean isRevoked() {
        return revokedAt != null;
    }

    /** Retires this token in favour of {@code successor}, which must belong to the same family. */
    void rotateTo(RefreshToken successor, Instant now) {
        if (isRevoked()) {
            throw new IllegalStateException("A revoked refresh token cannot be rotated");
        }
        if (!familyId.equals(successor.familyId)) {
            throw new IllegalArgumentException("The successor must belong to the same family");
        }
        this.revokedAt = now;
        this.replacedBy = successor.getId();
    }
}
