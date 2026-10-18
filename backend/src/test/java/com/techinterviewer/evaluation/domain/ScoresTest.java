package com.techinterviewer.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScoresTest {

    @ParameterizedTest(name = "{0} becomes {1}")
    @CsvSource({"0, 0.00", "100, 100.00", "57.5, 57.50", "57.555, 57.56", "57.554, 57.55", "0.004, 0.00", "99.995, 100.00", "33.3333, 33.33"})
    void aPercentageIsRoundedHalfUpToTwoDecimals(String given, String stored) {
        BigDecimal result = Scores.percent(new BigDecimal(given), "score");

        assertThat(result).isEqualTo(new BigDecimal(stored));
        assertThat(result.scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-1", "100.01", "101", "1000", "-0.005"})
    void aPercentageOutsideZeroToOneHundredIsRefused(String given) {
        assertThatIllegalArgumentException().isThrownBy(() -> Scores.percent(new BigDecimal(given), "score")).withMessageContaining("score");
    }

    @Test
    void roundingIsAppliedBeforeTheRangeCheck() {
        assertThat(Scores.percent(new BigDecimal("100.004"), "s")).isEqualTo(new BigDecimal("100.00"));
        assertThatIllegalArgumentException().isThrownBy(() -> Scores.percent(new BigDecimal("100.005"), "s"));
        assertThat(Scores.percent(new BigDecimal("-0.004"), "s")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aMissingValueIsRefusedWithItsName() {
        assertThatIllegalArgumentException().isThrownBy(() -> Scores.percent(null, "overallScore")).withMessageContaining("overallScore");
        assertThatIllegalArgumentException().isThrownBy(() -> Scores.difficulty(null, "avgDifficulty")).withMessageContaining("avgDifficulty");
    }

    @ParameterizedTest(name = "{0} becomes {1}")
    @CsvSource({"1, 1.00", "5, 5.00", "3.256, 3.26", "2.5, 2.50"})
    void anAverageDifficultyIsBetweenOneAndFive(String given, String stored) {
        assertThat(Scores.difficulty(new BigDecimal(given), "d")).isEqualTo(new BigDecimal(stored));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.99", "0", "5.01", "6", "-1"})
    void anAverageDifficultyOutsideOneToFiveIsRefused(String given) {
        assertThatIllegalArgumentException().isThrownBy(() -> Scores.difficulty(new BigDecimal(given), "d"));
    }
}
