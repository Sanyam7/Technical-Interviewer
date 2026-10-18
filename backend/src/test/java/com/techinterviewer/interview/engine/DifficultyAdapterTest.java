package com.techinterviewer.interview.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.TopicStat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DifficultyAdapterTest {

    private static final DifficultyBand BAND = new DifficultyBand(3, 2, 4);

    @ParameterizedTest
    @CsvSource({
        "50, 100, 80",
        "100, 0, 40",
        "0, 100, 60",
        "80, 80, 80",
        "0, 0, 0",
        "100, 100, 100",
        "33.33, 66.67, 53.33",
        "70, 90, 82"
    })
    void emaBlendsSixtyPercentNewScoreAndFortyPercentPrevious(double previous, double score, double expected) {
        assertThat(DifficultyAdapter.ema(previous, score)).isEqualTo(expected);
    }

    @Test
    void theFirstScoreOfATopicIsItsAverage() {
        assertThat(DifficultyAdapter.ema(null, 72.5)).isEqualTo(72.5);
    }

    @Test
    void emaRoundsHalfUpToTwoDecimals() {
        // 0.4 * 0.0125 = 0.005 exactly: half up gives 0.01, half even would give 0.00
        assertThat(DifficultyAdapter.ema(0.0125, 0.0)).isEqualTo(0.01);
        // 0.6 * 33.333 + 0.4 * 0 = 19.9998 -> 20.0
        assertThat(DifficultyAdapter.ema(0.0, 33.333)).isEqualTo(20.0);
    }

    @Test
    void emaRejectsScoresOutsideZeroToOneHundred() {
        assertThatThrownBy(() -> DifficultyAdapter.ema(50.0, 100.01)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DifficultyAdapter.ema(50.0, -0.01)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DifficultyAdapter.ema(100.01, 50.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DifficultyAdapter.ema(-1.0, 50.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DifficultyAdapter.ema(null, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DifficultyAdapter.ema(null, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "level {0} ema {1} last {2} -> {3}")
    @CsvSource({
        // level, ema, last score, expected level
        "3, 75, 70, 4",
        "3, 74.99, 70, 3",
        "3, 75, 69.99, 3",
        "3, 100, 100, 4",
        "3, 90, 60, 3",
        "3, 60, 95, 3",
        "3, 45, 50, 3",
        "3, 44.99, 50, 2",
        "3, 0, 0, 2",
        "3, 44.99, 100, 2",
        "4, 99, 99, 4",
        "2, 10, 10, 2",
        "5, 10, 10, 4",
        "1, 99, 99, 2",
        "3, 50, 50, 3"
    })
    void levelMovesByThresholdsAndStaysInsideTheBand(int level, double ema, double last, int expected) {
        assertThat(DifficultyAdapter.nextLevel(level, ema, last, BAND)).isEqualTo(expected);
    }

    @Test
    void aLevelOutsideTheBandIsPulledBackIntoIt() {
        assertThat(DifficultyAdapter.nextLevel(5, 60, 60, BAND)).isEqualTo(4);
        assertThat(DifficultyAdapter.nextLevel(1, 60, 60, BAND)).isEqualTo(2);
    }

    @Test
    void theNeutralScoreMovesNoLevel() {
        assertThat(DifficultyAdapter.NEUTRAL_SCORE).isBetween(DifficultyAdapter.LOWER_EMA_THRESHOLD, DifficultyAdapter.RAISE_EMA_THRESHOLD);
        assertThat(DifficultyAdapter.nextLevel(3, DifficultyAdapter.NEUTRAL_SCORE, DifficultyAdapter.NEUTRAL_SCORE, BAND)).isEqualTo(3);
    }

    @Test
    void aQuestionWithoutALevelOfItsOwnStartsAtTheBandStartBeforeAnyTechnicalAnswer() {
        assertThat(DifficultyAdapter.startLevel(InterviewMemory.empty(), BAND)).isEqualTo(3);
        assertThat(DifficultyAdapter.startLevel(withLevels(5, 5), BAND)).as("topic levels alone do not move it").isEqualTo(3);
    }

    @Test
    void aQuestionWithoutALevelOfItsOwnStartsAtTheCandidatesLevelInsideTheBand() {
        assertThat(DifficultyAdapter.startLevel(withOverall(4), BAND)).isEqualTo(4);
        assertThat(DifficultyAdapter.startLevel(withOverall(2), new DifficultyBand(3, 1, 5))).isEqualTo(2);
        assertThat(DifficultyAdapter.startLevel(withOverall(5), BAND)).as("clamped to the band").isEqualTo(4);
        assertThat(DifficultyAdapter.startLevel(withOverall(1), BAND)).as("clamped to the band").isEqualTo(2);
    }

    @Test
    void theNextStatAveragesTheScoreAndMovesTheLevelFromTheGivenOne() {
        TopicStat first = DifficultyAdapter.next(null, 3, 90, 1, BAND);
        assertThat(first).isEqualTo(new TopicStat(1, 90, 4, 90));
        TopicStat second = DifficultyAdapter.next(first, first.level(), 20, 2, BAND);
        assertThat(second).as("0.6 * 20 + 0.4 * 90 = 48: between the thresholds").isEqualTo(new TopicStat(2, 48, 4, 20));
        assertThat(DifficultyAdapter.next(second, second.level(), 10, 3, BAND)).as("0.6 * 10 + 0.4 * 48 = 25.2: down")
                .isEqualTo(new TopicStat(3, 25.2, 3, 10));
    }

    @Test
    void aScoreFromAnEvaluationInDoubtMovesTheAverageButNotTheLevel() {
        TopicStat start = new TopicStat(1, 50, 3, 50);

        assertThat(DifficultyAdapter.next(start, 3, 10, 2, BAND, false)).as("0.6 * 10 + 0.4 * 50 = 26: would lower, but is held")
                .isEqualTo(new TopicStat(2, 26, 3, 10));
        assertThat(DifficultyAdapter.next(start, 3, 100, 2, BAND, false)).as("0.6 * 100 + 0.4 * 50 = 80: would raise, but is held")
                .isEqualTo(new TopicStat(2, 80, 3, 100));
        assertThat(DifficultyAdapter.next(start, 3, 10, 2, BAND, true).level()).isEqualTo(2);
    }

    private static InterviewMemory withOverall(int level) {
        return new InterviewMemory(Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), null,
                new TopicStat(3, 60, level, 60), List.of());
    }

    private static InterviewMemory withLevels(int... levels) {
        Map<String, TopicStat> stats = new LinkedHashMap<>();
        for (int i = 0; i < levels.length; i++) {
            stats.put("java/t" + i, new TopicStat(1, 50, levels[i], 50));
        }
        return new InterviewMemory(stats, List.of(), List.of(), List.of(), Map.of(), Map.of(), null);
    }
}
