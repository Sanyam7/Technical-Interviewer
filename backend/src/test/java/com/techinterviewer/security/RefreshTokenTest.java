package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final UUID USER = UUID.randomUUID();

    private static RefreshToken token(UUID family, Instant expiresAt) {
        return new RefreshToken(USER, family, HASH, expiresAt, NOW);
    }

    @Test
    void aNewTokenIsActive() {
        RefreshToken token = token(UUID.randomUUID(), NOW.plusSeconds(60));

        assertThat(token.isRevoked()).isFalse();
        assertThat(token.getReplacedBy()).isNull();
        assertThat(token.isExpired(NOW)).isFalse();
        assertThat(token.getUserId()).isEqualTo(USER);
        assertThat(token.getTokenHash()).isEqualTo(HASH);
        assertThat(token.getId()).isNotNull();
    }

    @Test
    void aTokenExpiresExactlyAtItsExpiryInstant() {
        RefreshToken token = token(UUID.randomUUID(), NOW.plusSeconds(60));

        assertThat(token.isExpired(NOW.plusSeconds(59))).isFalse();
        assertThat(token.isExpired(NOW.plusSeconds(60))).isTrue();
        assertThat(token.isExpired(NOW.plusSeconds(61))).isTrue();
    }

    @Test
    void rotatingRetiresTheTokenAndRecordsItsSuccessor() {
        UUID family = UUID.randomUUID();
        RefreshToken current = token(family, NOW.plusSeconds(60));
        RefreshToken next = token(family, NOW.plusSeconds(120));

        current.rotateTo(next, NOW.plusSeconds(5));

        assertThat(current.isRevoked()).isTrue();
        assertThat(current.getReplacedBy()).isEqualTo(next.getId());
        assertThat(current.getRevokedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(next.getReplacedBy()).isNull();
        assertThat(next.isRevoked()).isFalse();
    }

    @Test
    void aSuccessorFromAnotherFamilyIsRefused() {
        RefreshToken current = token(UUID.randomUUID(), NOW.plusSeconds(60));
        RefreshToken stranger = token(UUID.randomUUID(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> current.rotateTo(stranger, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(current.getReplacedBy()).isNull();
        assertThat(current.isRevoked()).isFalse();
    }

    @Test
    void aRevokedTokenCannotBeRotatedAgain() {
        UUID family = UUID.randomUUID();
        RefreshToken current = token(family, NOW.plusSeconds(60));
        current.rotateTo(token(family, NOW.plusSeconds(120)), NOW);

        assertThatThrownBy(() -> current.rotateTo(token(family, NOW.plusSeconds(120)), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theHashMustBeSixtyFourLowerCaseHexCharacters() {
        for (String bad : new String[] {"", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64)}) {
            assertThatThrownBy(() -> new RefreshToken(USER, UUID.randomUUID(), bad, NOW.plusSeconds(60), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aTokenMustExpireAfterItIsCreated() {
        assertThatThrownBy(() -> token(UUID.randomUUID(), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> token(UUID.randomUUID(), NOW.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
