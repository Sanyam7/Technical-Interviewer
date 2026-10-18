package com.techinterviewer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkillRefTest {

    @Test
    void holdsTheIdentityAndDisplayValues() {
        SkillRef ref = new SkillRef(7, "spring-boot", "Spring Boot");

        assertThat(ref.id()).isEqualTo(7);
        assertThat(ref.slug()).isEqualTo("spring-boot");
        assertThat(ref.name()).isEqualTo("Spring Boot");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsIdsThatAreNotDatabaseIdentities(long id) {
        assertThatThrownBy(() -> new SkillRef(id, "java", "Java")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");
    }

    @Test
    void acceptsIdsBeyondTheIntRange() {
        assertThat(new SkillRef(Long.MAX_VALUE, "java", "Java").id()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsBlankOrOverlongSlugAndName() {
        assertThatThrownBy(() -> new SkillRef(1, "", "Java")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillRef(1, "a".repeat(61), "Java")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillRef(1, "java", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkillRef(1, "java", "n".repeat(81))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SkillRef(1, "a".repeat(60), "n".repeat(80)).slug()).hasSize(60);
    }
}
