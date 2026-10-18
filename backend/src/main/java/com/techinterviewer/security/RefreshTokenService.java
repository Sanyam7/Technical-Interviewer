package com.techinterviewer.security;

import com.techinterviewer.config.AppProperties;
import com.techinterviewer.user.User;
import com.techinterviewer.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>Rules:
 * <ul>
 *   <li>A login starts a new family; every use of a token replaces it by a new token of the same family
 *       (the lifetime restarts with each rotation).
 *   <li>A token that is unknown, expired, revoked or already replaced is never accepted. Presenting a token that
 *       was <b>already replaced or revoked</b> means somebody holds a copy of a retired token, so the whole family
 *       is revoked: whoever holds the newest token loses it too.
 *   <li>Two requests that present the same token at the same moment are decided one after the other (the token row
 *       is locked): the first rotates, the second finds a replaced token and therefore revokes the family. Exactly
 *       one of them succeeds and the family is dead afterwards.
 *   <li>A token of a disabled or deleted account is not accepted, and its family is revoked.
 * </ul>
 *
 * <p>Every method is one short transaction. Nothing is logged that could be used to guess a token, only ids.
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final SecureRandom random;
    private final Clock clock;
    private final Duration ttl;

    RefreshTokenService(
            RefreshTokenRepository tokens, UserRepository users, SecureRandom random, Clock clock, AppProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.random = random;
        this.clock = clock;
        this.ttl = properties.security().refreshCookie().ttl();
    }

    /** What a successful rotation yields: the account to issue an access token for, and the replacement token. */
    record Rotation(User user, IssuedRefreshToken token) {
    }

    /** Starts a new family for the user and returns its first token. Joins the caller's transaction. */
    @Transactional
    IssuedRefreshToken startFamily(UUID userId) {
        Instant now = clock.instant();
        tokens.deleteExpired(userId, now);
        String value = OpaqueTokens.generate(random);
        RefreshToken first = tokens.save(newToken(value, userId, UUID.randomUUID(), now));
        return new IssuedRefreshToken(value, first.getExpiresAt());
    }

    /**
     * Exchanges a presented token for a new one.
     *
     * @return the rotation, or empty when the token must be refused (see the class comment); the caller answers
     *         401 in that case. A refusal still commits any family revocation it caused, which is why this is
     *         not signalled by an exception that would roll the transaction back
     */
    @Transactional
    Optional<Rotation> rotate(String presentedValue) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = tokens.findByTokenHashForUpdate(OpaqueTokens.hash(presentedValue));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken current = found.get();
        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Retired refresh token presented again: family {} of user {} revoked ({} tokens)",
                    current.getFamilyId(), current.getUserId(), revoked);
            return Optional.empty();
        }
        if (current.isExpired(now)) {
            return Optional.empty();
        }
        Optional<User> owner = users.findById(current.getUserId()).filter(User::isEnabled);
        if (owner.isEmpty()) {
            tokens.revokeFamily(current.getFamilyId(), now);
            log.info("Refresh token of a disabled or deleted account refused: family {} revoked", current.getFamilyId());
            return Optional.empty();
        }
        String value = OpaqueTokens.generate(random);
        RefreshToken successor = tokens.save(newToken(value, current.getUserId(), current.getFamilyId(), now));
        current.rotateTo(successor, now);
        tokens.deleteExpired(current.getUserId(), now);
        return Optional.of(new Rotation(owner.get(), new IssuedRefreshToken(value, successor.getExpiresAt())));
    }

    /** Deletes every expired token (called by the scheduled purge); returns how many rows were removed. */
    @Transactional
    int purgeExpired() {
        return tokens.deleteAllExpired(clock.instant());
    }

    /** Revokes the family of the presented token. Unknown or already revoked tokens are ignored: logout is idempotent. */
    @Transactional
    void revokeFamilyOf(String presentedValue) {
        tokens.findByTokenHash(OpaqueTokens.hash(presentedValue))
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    private RefreshToken newToken(String value, UUID userId, UUID familyId, Instant now) {
        return new RefreshToken(userId, familyId, OpaqueTokens.hash(value), now.plus(ttl), now);
    }
}
