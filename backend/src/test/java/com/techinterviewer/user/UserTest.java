package com.techinterviewer.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void aNewAccountIsAnEnabledCandidate() {
        User user = User.candidate("ada@example.com", "{bcrypt}hash", "Ada", NOW);

        assertThat(user.getRole()).isEqualTo(Role.CANDIDATE);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(user.getDisplayName()).isEqualTo("Ada");
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        assertThat(user.getId()).isNotNull();
        assertThat(user.isNew()).isTrue();
    }

    @Test
    void theStringFormNeverShowsTheHashOrTheEmail() {
        User user = User.candidate("ada@example.com", "{bcrypt}secret-hash", "Ada", NOW);

        assertThat(user.toString()).doesNotContain("secret-hash").doesNotContain("ada@example.com");
    }

    @Test
    void invalidValuesCannotBuildAnAccount() {
        assertThatThrownBy(() -> User.candidate("", "h", "Ada", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.candidate("a".repeat(255), "h", "Ada", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.candidate("a@b.co", " ", "Ada", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.candidate("a@b.co", "h".repeat(101), "Ada", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.candidate("a@b.co", "h", "", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.candidate("a@b.co", "h", "n".repeat(81), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.candidate("a@b.co", "h", "Ada", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
