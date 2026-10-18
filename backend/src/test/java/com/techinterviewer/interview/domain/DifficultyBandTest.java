package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DifficultyBandTest {

    @ParameterizedTest(name = "start {0} in [{1}, {2}]")
    @CsvSource({"2, 1, 3", "3, 2, 4", "4, 3, 5", "1, 1, 1", "5, 5, 5", "3, 1, 5"})
    void validBands(int start, int min, int max) {
        DifficultyBand band = new DifficultyBand(start, min, max);

        assertThat(band.start()).isEqualTo(start);
    }

    @ParameterizedTest(name = "start {0} in [{1}, {2}] is invalid")
    @CsvSource({"0, 1, 3", "6, 1, 5", "3, 0, 4", "3, 2, 6", "1, 2, 4", "5, 2, 4", "3, 4, 2"})
    void invalidBands(int start, int min, int max) {
        assertThatThrownBy(() -> new DifficultyBand(start, min, max)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "clamp({0}) in [2, 4] = {1}")
    @CsvSource({"1, 2", "2, 2", "3, 3", "4, 4", "5, 4", "-9, 2", "99, 4"})
    void clampKeepsALevelInsideTheBand(int level, int expected) {
        assertThat(new DifficultyBand(3, 2, 4).clamp(level)).isEqualTo(expected);
    }

    @Test
    void limitsAreTheFiveLevelsOfTheSchema() {
        assertThat(DifficultyBand.LOWEST).isEqualTo(1);
        assertThat(DifficultyBand.HIGHEST).isEqualTo(5);
    }
}
