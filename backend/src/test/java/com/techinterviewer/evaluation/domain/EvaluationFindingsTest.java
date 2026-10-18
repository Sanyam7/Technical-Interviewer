package com.techinterviewer.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvaluationFindingsTest {

    private static final CriterionScore CORRECTNESS = new CriterionScore(Criterion.CORRECTNESS, 3, 0.5, "ok");
    private static final CriterionScore DEPTH = new CriterionScore(Criterion.DEPTH, 2, 0.5, "");

    private static EvaluationFindings findings(List<CriterionScore> criteria, List<KeyPointAssessment> keyPoints, List<String> claims, String summary) {
        return new EvaluationFindings(criteria, keyPoints, List.of(), claims, summary, Set.of());
    }

    @Test
    void validFindingsAreKeptAndImmutable() {
        List<CriterionScore> criteria = new ArrayList<>(List.of(CORRECTNESS, DEPTH));
        EvaluationFindings findings = new EvaluationFindings(
                criteria, List.of(), List.of(), List.of("a claim"), "Summary.", Set.of(EvaluationFlag.OFF_TOPIC));
        criteria.clear();

        assertThat(findings.criterionScores()).containsExactly(CORRECTNESS, DEPTH);
        assertThat(findings.candidateClaims()).containsExactly("a claim");
        assertThat(findings.summary()).isEqualTo("Summary.");
        assertThat(findings.flags()).containsExactly(EvaluationFlag.OFF_TOPIC);
        assertThatThrownBy(() -> findings.criterionScores().add(DEPTH)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> findings.flags().add(EvaluationFlag.LANGUAGE_MISMATCH)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void atLeastOneCriterionMustBeRated() {
        assertThatIllegalArgumentException().isThrownBy(() -> findings(List.of(), List.of(), List.of(), "s")).withMessageContaining("criterionScores");
    }

    @Test
    void aCriterionCannotBeRatedTwice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> findings(List.of(CORRECTNESS, new CriterionScore(Criterion.CORRECTNESS, 1, 0.2, "")), List.of(), List.of(), "s"))
                .withMessageContaining("CORRECTNESS");
    }

    @Test
    void aKeyPointCannotBeAssessedTwice() {
        List<KeyPointAssessment> twice = List.of(
                new KeyPointAssessment("kp1", KeyPointStatus.COVERED, "q", true),
                new KeyPointAssessment("kp1", KeyPointStatus.MISSING, null, false));

        assertThatIllegalArgumentException().isThrownBy(() -> findings(List.of(CORRECTNESS), twice, List.of(), "s")).withMessageContaining("kp1");
    }

    @Test
    void theSummaryMustNotBeBlankOrLongerThanTheLimit() {
        assertThatIllegalArgumentException().isThrownBy(() -> findings(List.of(CORRECTNESS), List.of(), List.of(), " ")).withMessageContaining("summary");
        assertThat(findings(List.of(CORRECTNESS), List.of(), List.of(), "s".repeat(4000)).summary()).hasSize(4000);
        assertThatIllegalArgumentException().isThrownBy(() -> findings(List.of(CORRECTNESS), List.of(), List.of(), "s".repeat(4001)));
    }

    @Test
    void claimsMustNotBeBlankOrTooLong() {
        assertThatIllegalArgumentException().isThrownBy(() -> findings(List.of(CORRECTNESS), List.of(), List.of(" "), "s")).withMessageContaining("claim");
        assertThat(findings(List.of(CORRECTNESS), List.of(), List.of("c".repeat(300)), "s").candidateClaims()).hasSize(1);
        assertThatIllegalArgumentException().isThrownBy(() -> findings(List.of(CORRECTNESS), List.of(), List.of("c".repeat(301)), "s"));
    }

    @Test
    void missingCollectionsBecomeEmptyOnes() {
        EvaluationFindings findings = new EvaluationFindings(List.of(CORRECTNESS), null, null, null, "s", null);

        assertThat(findings.keyPoints()).isEmpty();
        assertThat(findings.factualErrors()).isEmpty();
        assertThat(findings.candidateClaims()).isEmpty();
        assertThat(findings.flags()).isEmpty();
    }
}
