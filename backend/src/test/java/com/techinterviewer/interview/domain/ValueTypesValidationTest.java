package com.techinterviewer.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Impossible values cannot be built: scores 0..100, levels 1..5, non-blank ids, bounded texts. */
class ValueTypesValidationTest {

    @ParameterizedTest(name = "ema {0} level {1} lastScore {2} valid={3}")
    @CsvSource({
        "0, 1, 0, true", "100, 5, 100, true", "71.5, 3, 78, true",
        "-0.1, 3, 50, false", "100.1, 3, 50, false", "50, 0, 50, false", "50, 6, 50, false", "50, 3, -1, false", "50, 3, 101, false",
        "NaN, 3, 50, false", "50, 3, NaN, false"
    })
    void topicStatLimits(double ema, int level, double lastScore, boolean valid) {
        if (valid) {
            assertThat(new TopicStat(1, ema, level, lastScore).level()).isEqualTo(level);
        } else {
            assertThatThrownBy(() -> new TopicStat(1, ema, level, lastScore)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new TopicStat(-1, 50, 3, 50)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"kp1", "kp_2", "key-point.3", "A", "0123456789012345678901234567890123456789"})
    void validKeyPointIds(String id) {
        assertThat(new ExpectedKeyPoint(id, "text", List.of()).id()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "kp 1", "kp/1", "kp;1", "01234567890123456789012345678901234567890", "kp\n1", "ké"})
    void invalidKeyPointIds(String id) {
        assertThatThrownBy(() -> new ExpectedKeyPoint(id, "text", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyPointTextAndChunksAreChecked() {
        assertThatThrownBy(() -> new ExpectedKeyPoint("kp1", " ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpectedKeyPoint("kp1", "x".repeat(501), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ExpectedKeyPoint("kp1", "x".repeat(500), null).sourceChunkIds()).isEmpty();
        List<UUID> chunks = new ArrayList<>(List.of(UUID.randomUUID()));
        ExpectedKeyPoint keyPoint = new ExpectedKeyPoint("kp1", "t", chunks);
        chunks.clear();
        assertThat(keyPoint.sourceChunkIds()).hasSize(1);
    }

    @Test
    void questionDetailsAreBounded() {
        assertThat(new QuestionDetails(null, null, null, null).constraints()).isEmpty();
        assertThatThrownBy(() -> new QuestionDetails(List.of(" "), null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestionDetails(java.util.Collections.nCopies(21, "c"), null, null, null))
                .hasMessageContaining("constraints");
        assertThatThrownBy(() -> new QuestionDetails(null, java.util.Collections.nCopies(11, new CodingExample("a", "b", null)), null, null))
                .hasMessageContaining("examples");
        assertThatThrownBy(() -> new QuestionDetails(null, null, "", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodingExample(" ", "b", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodingExample("a", "b", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimsAnswerSummariesAndProgressAreChecked() {
        assertThatThrownBy(() -> new Claim(" ", false, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim("x", false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim("x".repeat(301), false, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerSummary(null, "a/b", "s", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerSummary(UUID.randomUUID(), "a/b", " ", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerSummary(UUID.randomUUID(), "a/b", "s", 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerSummary(UUID.randomUUID(), "a/b", "s", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AnswerSummary(UUID.randomUUID(), null, "s", 0).topic()).isNull();
        assertThatThrownBy(() -> new SectionProgress(-1, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SectionProgress(0, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
