package com.techinterviewer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The sentence a candidate reads next to a rating the server changed: one per rule, naming both ratings. */
class ScoreNotesTest {

    private static CriterionScore lowered(Criterion criterion, int from, int to, String rule) {
        return new CriterionScore(criterion, to, 0.25, "The evaluator's reasons.", from, rule);
    }

    @ParameterizedTest
    @EnumSource(value = RatingRules.Rule.class, mode = EnumSource.Mode.EXCLUDE, names = "TEST_RESULTS")
    void everyCappingRuleExplainsItselfAndNamesBothRatings(RatingRules.Rule rule) {
        String note = ScoreNotes.note(lowered(Criterion.CORRECTNESS, 4, 1, rule.name()), null);

        assertThat(note).startsWith("Lowered from 4 to 1: ").endsWith(".").doesNotContain("consistency rule");
    }

    @Test
    void theNotesOfTheRulesAreAllDifferent() {
        Set<String> notes = Arrays.stream(RatingRules.Rule.values())
                .filter(rule -> rule != RatingRules.Rule.TEST_RESULTS)
                .map(rule -> ScoreNotes.note(lowered(Criterion.CORRECTNESS, 3, 2, rule.name()), null))
                .collect(Collectors.toSet());

        assertThat(notes).hasSize(RatingRules.Rule.values().length - 1);
    }

    @Test
    void aFactualErrorReadsDifferentlyForCorrectnessAndForADesignDimension() {
        assertThat(ScoreNotes.note(lowered(Criterion.CORRECTNESS, 4, 2, "FACTUAL_ERRORS"), null)).contains("fully correct");
        assertThat(ScoreNotes.note(lowered(Criterion.CACHING, 4, 2, "FACTUAL_ERRORS"), null)).contains("this part of the design");
    }

    @Test
    void edgeCaseTestsAreCountedWhenTheResultsAreKnown() {
        TestEvidence tests = new TestEvidence(true, 5, 5, 1, 4, false);

        assertThat(ScoreNotes.note(lowered(Criterion.EDGE_CASES, 4, 2, "EDGE_CASE_TESTS"), tests)).endsWith("1 of 4 edge-case tests passed.");
        assertThat(ScoreNotes.note(lowered(Criterion.EDGE_CASES, 4, 2, "EDGE_CASE_TESTS"), null)).endsWith("edge-case tests failed.");
    }

    @Test
    void aRuleThisVersionDoesNotKnowStillGetsASentence() {
        assertThat(ScoreNotes.note(lowered(Criterion.DEPTH, 3, 2, "A_RULE_FROM_A_LATER_VERSION"), null))
                .isEqualTo("Lowered from 3 to 2: a consistency rule applied.");
    }

    @Test
    void aRatingTakenAsTheEvaluatorGaveItHasNoNote() {
        assertThat(ScoreNotes.note(new CriterionScore(Criterion.DEPTH, 3, 0.25, "Reasons."), null)).isNull();
    }

    @Test
    void aRatingTheTestsDecidedSaysWhatTheTestsShowedAndWhatTheReviewerThought() {
        CriterionScore measured = CriterionScore.measured(Criterion.CORRECTNESS, 2, 0.4, "Reasons.", 4, "TEST_RESULTS");

        assertThat(ScoreNotes.note(measured, new TestEvidence(true, 3, 6, 0, 2, false)))
                .isEqualTo("Set by the tests: 3 of 6 functional tests passed. The reviewer's own rating was 4.");
        assertThat(ScoreNotes.note(measured, new TestEvidence(false, 0, 6, 0, 2, false)))
                .isEqualTo("Set by the tests: the code did not compile, so no test passed. The reviewer's own rating was 4.");
        assertThat(ScoreNotes.note(measured, null)).isEqualTo("Set by the tests. The reviewer's own rating was 4.");
    }
}
