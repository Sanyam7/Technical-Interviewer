package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.ReportScores.Coverage;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.Recommendation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class RecommendationPolicyTest {

    @ParameterizedTest
    @CsvSource({
        "100, STRONG",
        "80.01, STRONG",
        "80, STRONG",
        "79.99, PROFICIENT",
        "65.01, PROFICIENT",
        "65, PROFICIENT",
        "64.99, DEVELOPING",
        "45.01, DEVELOPING",
        "45, DEVELOPING",
        "44.99, NOT_YET",
        "0.01, NOT_YET",
        "0, NOT_YET"
    })
    void bandsHaveTheirDocumentedBoundaries(double score, Recommendation expected) {
        assertThat(RecommendationPolicy.recommend(score, Confidence.HIGH)).isEqualTo(expected);
        assertThat(RecommendationPolicy.recommend(score, Confidence.MEDIUM)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"95", "70", "50", "10", "0"})
    void lowConfidenceGivesNoRecommendationWhateverTheScore(double score) {
        assertThat(RecommendationPolicy.recommend(score, Confidence.LOW)).isNull();
    }

    @ParameterizedTest
    @EnumSource(Confidence.class)
    void insufficientEvidenceGivesNoRecommendation(Confidence confidence) {
        assertThat(RecommendationPolicy.recommend(null, confidence)).isNull();
    }

    @Test
    void recommendationOfAReportReadsItsOverallScoreAndConfidence() {
        Coverage coverage = new Coverage(0, 0, 0, 0, 0);

        assertThat(RecommendationPolicy.recommend(
                new ReportScores(72.5, Confidence.HIGH, List.of(), List.of(), List.of(), coverage, List.of())))
                .isEqualTo(Recommendation.PROFICIENT);
        assertThat(RecommendationPolicy.recommend(
                new ReportScores(72.5, Confidence.LOW, List.of(), List.of(), List.of(), coverage, List.of())))
                .isNull();
        assertThat(RecommendationPolicy.recommend(
                new ReportScores(null, Confidence.LOW, List.of(), List.of(), List.of(), coverage, List.of())))
                .isNull();
    }
}
