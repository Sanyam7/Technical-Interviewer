package com.techinterviewer.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EvaluationValueTypesTest {

    @ParameterizedTest(name = "rating {0} valid={1}")
    @CsvSource({"0, true", "1, true", "4, true", "-1, false", "5, false", "9, false", "100, false"})
    void ratingsRunFromZeroToFour(int rating, boolean valid) {
        if (valid) {
            assertThat(new CriterionScore(Criterion.DEPTH, rating, 0.25, "ok").rating()).isEqualTo(rating);
        } else {
            assertThatThrownBy(() -> new CriterionScore(Criterion.DEPTH, rating, 0.25, "ok")).hasMessageContaining("rating");
        }
    }

    @ParameterizedTest(name = "weight {0} valid={1}")
    @CsvSource({"0.0001, true", "0.5, true", "1.0, true", "0.0, false", "-0.1, false", "1.0001, false", "NaN, false"})
    void weightsAreAboveZeroAndAtMostOne(double weight, boolean valid) {
        if (valid) {
            assertThat(new CriterionScore(Criterion.DEPTH, 2, weight, "").weight()).isEqualTo(weight);
        } else {
            assertThatThrownBy(() -> new CriterionScore(Criterion.DEPTH, 2, weight, "")).hasMessageContaining("weight");
        }
    }

    @Test
    void criterionScoreRejectsMissingPartsAndLongRationales() {
        assertThatThrownBy(() -> new CriterionScore(null, 2, 0.5, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CriterionScore(Criterion.DEPTH, 2, 0.5, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CriterionScore(Criterion.DEPTH, 2, 0.5, "x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CriterionScore(Criterion.DEPTH, 2, 0.5, "x".repeat(2000)).rationale()).hasSize(2000);
    }

    @Test
    void keyPointAssessmentsAreChecked() {
        assertThat(new KeyPointAssessment("kp1", KeyPointStatus.MISSING, null, false).evidenceQuote()).isNull();
        assertThatThrownBy(() -> new KeyPointAssessment(" ", KeyPointStatus.COVERED, "q", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyPointAssessment("kp1", null, "q", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyPointAssessment("kp1", KeyPointStatus.COVERED, " ", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factualErrorsNeedAClaimAndACorrection() {
        assertThat(new FactualError("c", "k", null).sourceChunkIds()).isEmpty();
        assertThat(new FactualError("c", "k", List.of(UUID.randomUUID())).sourceChunkIds()).hasSize(1);
        assertThatThrownBy(() -> new FactualError(" ", "k", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FactualError("c", "", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thereAreTwentyEightCriteria() {
        assertThat(Criterion.values()).hasSize(28);
    }
}
