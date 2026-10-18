package com.techinterviewer.evaluation;

import static com.techinterviewer.evaluation.ReportAnswers.coding;
import static com.techinterviewer.evaluation.ReportAnswers.design;
import static com.techinterviewer.evaluation.ReportAnswers.followUp;
import static com.techinterviewer.evaluation.ReportAnswers.id;
import static com.techinterviewer.evaluation.ReportAnswers.intro;
import static com.techinterviewer.evaluation.ReportAnswers.skippedTechnical;
import static com.techinterviewer.evaluation.ReportAnswers.technical;
import static com.techinterviewer.evaluation.ReportAnswers.unassessableCoding;
import static com.techinterviewer.evaluation.ReportAnswers.unassessableDesign;
import static com.techinterviewer.evaluation.ReportAnswers.unassessableFollowUp;
import static com.techinterviewer.evaluation.ReportAnswers.unassessableIntro;
import static com.techinterviewer.evaluation.ReportAnswers.unassessableTechnical;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.UnassessableAnswer;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Answers that could not be assessed are neither bad answers nor skipped ones: they are left out of every score, never
 * counted as zero, and the weight they would have had is reported as not assessed.
 */
class ReportAggregatorUnassessableTest {

    private static final String JAVA = "java/concurrency";
    private static final String SPRING = "spring-boot/transactions";

    @Test
    void anUnassessableAnswerIsLeftOutOfTheScoreInsteadOfCountingAsZero() {
        ReportScores scores = ReportAggregator.aggregate(List.of(technical(1, JAVA, 3, 80), unassessableTechnical(2, JAVA, 3)));

        assertThat(scores.overallScore()).as("80 over the one assessable turn, not 40").isEqualTo(80.0);
        assertThat(scores.topics()).singleElement().satisfies(topic -> assertThat(topic.evidenceCount()).isEqualTo(1));
        assertThat(scores.assessment().intendedWeight()).isEqualTo(6);
        assertThat(scores.assessment().assessedWeight()).isEqualTo(3);
        assertThat(scores.assessment().unassessable()).containsExactly(
                new UnassessableAnswer(id(2), 2, Section.QUESTIONING, EvaluationFailure.PROVIDER_FAILURE));
    }

    @Test
    void theMeanIsRenormalisedOverTheAssessedWeight() {
        // difficulty 2 scored 100, difficulty 4 unassessable, difficulty 1 scored 40: (2*100 + 1*40) / (2 + 1)
        ReportScores scores = ReportAggregator.aggregate(List.of(
                technical(1, JAVA, 2, 100), unassessableTechnical(2, JAVA, 4), technical(3, SPRING, 1, 40)));

        assertThat(scores.overallScore()).isEqualTo(80.0);
        assertThat(scores.assessment().assessedWeight()).isEqualTo(3);
        assertThat(scores.assessment().intendedWeight()).isEqualTo(7);
    }

    @Test
    void whenEveryAnswerWasAssessedTheAssessmentIsFullAndNothingIsListed() {
        ReportScores scores = ReportAggregator.aggregate(List.of(intro(1, 70), technical(2, JAVA, 3, 60), coding(3, 2, 50)));

        assertThat(scores.assessment().intendedWeight()).as("the introduction carries no weight").isEqualTo(5);
        assertThat(scores.assessment().assessedWeight()).isEqualTo(5);
        assertThat(scores.assessment().unassessable()).isEmpty();
    }

    @Test
    void aSkippedRootQuestionIsStillAssessedAsZeroWhileAnUnassessableOneIsNot() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                technical(1, JAVA, 2, 100), skippedTechnical(2, JAVA, 2), unassessableTechnical(3, SPRING, 2)));

        assertThat(scores.overallScore()).as("(100 + 0) / 2: the skip scores zero, the unassessable answer does not exist").isEqualTo(50.0);
        assertThat(scores.assessment().assessedWeight()).isEqualTo(4);
        assertThat(scores.assessment().intendedWeight()).isEqualTo(6);
    }

    @Test
    void anUnassessableIntroductionHasNoSectionScoreAndNoWeightButIsListed() {
        ReportScores scores = ReportAggregator.aggregate(List.of(unassessableIntro(1), technical(2, JAVA, 3, 70)));

        assertThat(scores.sections()).extracting(ReportScores.SectionScore::section).containsExactly(Section.QUESTIONING);
        assertThat(scores.assessment().intendedWeight()).isEqualTo(3);
        assertThat(scores.assessment().assessedWeight()).isEqualTo(3);
        assertThat(scores.assessment().unassessable()).extracting(UnassessableAnswer::section).containsExactly(Section.INTRODUCTION);
        assertThat(scores.coverage().questionsAnswered()).as("it was answered").isEqualTo(2);
    }

    @Test
    void anUnassessableFollowUpIsLeftOutAndItsRootStays() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                technical(1, JAVA, 3, 90), unassessableFollowUp(2, 1, Section.QUESTIONING, JAVA, 3), followUp(3, 1, Section.QUESTIONING, JAVA, 3, 70)));

        assertThat(scores.overallScore()).isEqualTo(80.0);
        assertThat(scores.assessment().intendedWeight()).isEqualTo(9);
        assertThat(scores.assessment().assessedWeight()).isEqualTo(6);
        assertThat(scores.assessment().unassessable()).extracting(UnassessableAnswer::reason).containsExactly(EvaluationFailure.INVALID_OUTPUT);
    }

    @Test
    void aDesignThreadWhoseLastAnswerIsUnassessableIsUnassessableAsAWholeBecauseTheEvaluationsAreCumulative() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                technical(1, JAVA, 3, 60),
                design(2, 4, 30),
                unassessableFollowUp(3, 2, Section.SYSTEM_DESIGN, null, 4)));

        assertThat(scores.sections()).as("the earlier, lower evaluation of the thread is not its score")
                .extracting(ReportScores.SectionScore::section).containsExactly(Section.QUESTIONING);
        assertThat(scores.overallScore()).isEqualTo(60.0);
        assertThat(scores.assessment().intendedWeight()).isEqualTo(7);
        assertThat(scores.assessment().assessedWeight()).isEqualTo(3);
    }

    @Test
    void aDesignThreadWhoseEarlierAnswerIsUnassessableButWhoseLastIsAssessedUsesTheLastScoreAndStillListsTheEarlierOne() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                technical(1, JAVA, 3, 60),
                new ReportAnswer(id(2), id(2), 2, QuestionKind.SYSTEM_DESIGN, Section.SYSTEM_DESIGN, null, 4, false, null, Set.of(),
                        EvaluationFailure.PROVIDER_FAILURE),
                ReportAnswers.followUp(3, 2, Section.SYSTEM_DESIGN, null, 4, 90)));

        assertThat(scores.sections()).extracting(ReportScores.SectionScore::section).containsExactly(Section.QUESTIONING, Section.SYSTEM_DESIGN);
        assertThat(scores.assessment().assessedWeight()).as("the thread counts as assessed").isEqualTo(7);
        assertThat(scores.assessment().unassessable()).extracting(UnassessableAnswer::questionId).containsExactly(id(2));
    }

    @Test
    void whenNothingTechnicalCouldBeAssessedThereIsNoScoreNoSkillAndLowConfidenceButTheAssessmentSaysSo() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                intro(1, 80), unassessableTechnical(2, JAVA, 3), unassessableCoding(3, 4), unassessableDesign(4, 2)));

        assertThat(scores.overallScore()).isNull();
        assertThat(scores.insufficientEvidence()).isTrue();
        assertThat(scores.overallConfidence()).isEqualTo(Confidence.LOW);
        assertThat(scores.skills()).isEmpty();
        assertThat(scores.topics()).isEmpty();
        assertThat(scores.sections()).extracting(ReportScores.SectionScore::section).containsExactly(Section.INTRODUCTION);
        assertThat(scores.assessment().assessedWeight()).isZero();
        assertThat(scores.assessment().intendedWeight()).isEqualTo(9);
        assertThat(scores.assessment().unassessable()).extracting(UnassessableAnswer::reason)
                .containsExactly(EvaluationFailure.PROVIDER_FAILURE, EvaluationFailure.SAFETY_BLOCK, EvaluationFailure.PROVIDER_FAILURE);
    }

    @Test
    void confidenceFollowsTheNumberOfAssessableTurnsNotTheNumberOfAnswers() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                technical(1, JAVA, 3, 70), technical(2, JAVA, 3, 72), unassessableTechnical(3, JAVA, 3), unassessableTechnical(4, JAVA, 3)));

        assertThat(scores.overallConfidence()).as("two assessed turns, not four").isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void unassessableAnswersCarryNoIntegrityNotes() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                technical(1, JAVA, 3, 70, EvaluationFlag.OFF_TOPIC), unassessableTechnical(2, JAVA, 3)));

        assertThat(scores.integrityNotes()).singleElement().satisfies(note -> assertThat(note.questionIds()).containsExactly(id(1)));
    }

    @Test
    void anAnswerIsEitherScoredSkippedOrUnassessableNeverTwoOfThose() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportAnswer(
                id(1), id(1), 1, QuestionKind.TECHNICAL, Section.QUESTIONING, JAVA, 3, false, 50.0, Set.of(), EvaluationFailure.PROVIDER_FAILURE));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportAnswer(
                id(1), id(1), 1, QuestionKind.TECHNICAL, Section.QUESTIONING, JAVA, 3, true, null, Set.of(), EvaluationFailure.PROVIDER_FAILURE));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportAnswer(
                id(1), id(1), 1, QuestionKind.TECHNICAL, Section.QUESTIONING, JAVA, 3, false, null, Set.of(), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReportAnswer(
                id(1), id(1), 1, QuestionKind.TECHNICAL, Section.QUESTIONING, JAVA, 3, false, null, Set.of(EvaluationFlag.OFF_TOPIC),
                EvaluationFailure.PROVIDER_FAILURE));
    }
}
