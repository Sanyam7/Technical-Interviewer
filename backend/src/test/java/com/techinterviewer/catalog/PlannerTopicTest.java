package com.techinterviewer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlannerTopicTest {

    private static PlannerTopic topic(int min, int max, double weight) {
        return new PlannerTopic("java", "Java", "generics", "Generics", min, max, weight);
    }

    @Test
    void keyJoinsSkillAndTopicSlugsBecauseATopicSlugIsOnlyUniqueWithinItsSkill() {
        assertThat(topic(2, 4, 1.0).key()).isEqualTo("java/generics");
    }

    @ParameterizedTest
    @CsvSource({"1,1", "1,5", "5,5", "2,4"})
    void acceptsEveryDifficultyWindowInsideOneToFive(int min, int max) {
        PlannerTopic topic = topic(min, max, 0.5);

        assertThat(topic.minDifficulty()).isEqualTo(min);
        assertThat(topic.maxDifficulty()).isEqualTo(max);
    }

    @ParameterizedTest
    @CsvSource({"0,3", "6,6", "3,2", "-1,4", "1,6", "1,0"})
    void rejectsWindowsOutsideOneToFiveOrInverted(int min, int max) {
        assertThatThrownBy(() -> topic(min, max, 1.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0", "-0.5", "NaN", "-Infinity"})
    void rejectsWeightsThatAreNotPositive(double weight) {
        assertThatThrownBy(() -> topic(1, 3, weight)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("weight");
    }

    @Test
    void rejectsBlankNames() {
        assertThatThrownBy(() -> new PlannerTopic(" ", "Java", "generics", "Generics", 1, 2, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skillSlug");
        assertThatThrownBy(() -> new PlannerTopic("java", "Java", "", "Generics", 1, 2, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("slug");
    }
}
