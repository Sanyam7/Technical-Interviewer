package com.techinterviewer.evaluation;

import static com.techinterviewer.evaluation.ReportAnswers.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Recommendation;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The report's assessment areas and the fundamentals cap of the recommendation (AI_ARCHITECTURE.md section 2.15). */
class ReportAreasTest {

    private static ReportAnswer typed(int seq, int rootSeq, QuestionType type, int difficulty, double score, @Nullable Integer communication,
            EvaluationFlag... flags) {
        QuestionKind kind = seq == rootSeq ? QuestionKind.TECHNICAL : QuestionKind.FOLLOW_UP;
        return new ReportAnswer(id(seq), id(rootSeq), seq, kind, Section.QUESTIONING, "java/collections", difficulty, false, score, Set.of(flags),
                null, AssessmentArea.of(Section.QUESTIONING, type, false), communication);
    }

    private static ReportAnswer intro(int seq, int communication) {
        return new ReportAnswer(id(seq), id(seq), seq, QuestionKind.INTRO, Section.INTRODUCTION, null, 3, false, 75.0, Set.of(), null, null,
                communication);
    }

    private static ReportScores.AreaResult area(ReportScores scores, AssessmentArea area) {
        return scores.area(area).orElseThrow();
    }

    @Test
    void everyQuestionTypeCountsInItsArea() {
        assertThat(AssessmentArea.of(Section.QUESTIONING, QuestionType.CONCEPT, false)).isEqualTo(AssessmentArea.FUNDAMENTALS);
        assertThat(AssessmentArea.of(Section.QUESTIONING, QuestionType.INTERNALS, false)).isEqualTo(AssessmentArea.FUNDAMENTALS);
        assertThat(AssessmentArea.of(Section.QUESTIONING, QuestionType.SCENARIO, false)).isEqualTo(AssessmentArea.PRACTICAL);
        assertThat(AssessmentArea.of(Section.QUESTIONING, QuestionType.DEBUGGING, false)).isEqualTo(AssessmentArea.PROBLEM_SOLVING);
        assertThat(AssessmentArea.of(Section.QUESTIONING, QuestionType.EXPERIENCE, true)).isEqualTo(AssessmentArea.PROJECT);
        assertThat(AssessmentArea.of(Section.CODING, null, false)).isEqualTo(AssessmentArea.PROBLEM_SOLVING);
        assertThat(AssessmentArea.of(Section.SYSTEM_DESIGN, null, false)).isEqualTo(AssessmentArea.PROBLEM_SOLVING);
        assertThat(AssessmentArea.of(Section.INTRODUCTION, null, false)).isNull();
        assertThat(AssessmentArea.of(Section.QUESTIONING, null, false)).as("asked before types existed").isEqualTo(AssessmentArea.FUNDAMENTALS);
        assertThat(AssessmentArea.of(Section.QUESTIONING, null, true)).as("anchored before types existed").isEqualTo(AssessmentArea.PROJECT);
    }

    @Test
    void eachAreaIsScoredFromItsOwnAnswersWithItsShareOfTheOverall() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                intro(1, 3),
                typed(2, 2, QuestionType.INTERNALS, 3, 80, 4),
                typed(3, 2, QuestionType.INTERNALS, 4, 60, 2),
                typed(4, 4, QuestionType.CONCEPT, 3, 70, 3),
                typed(5, 5, QuestionType.SCENARIO, 3, 50, 3, EvaluationFlag.LOW_EVALUATOR_CONFIDENCE),
                typed(6, 6, QuestionType.EXPERIENCE, 2, 90, 4)));

        ReportScores.AreaResult fundamentals = area(scores, AssessmentArea.FUNDAMENTALS);
        assertThat(fundamentals.score()).as("(80*3 + 60*4 + 70*3) / 10").isEqualTo(69.0);
        assertThat(fundamentals.evidenceCount()).isEqualTo(3);
        assertThat(fundamentals.shareOfOverall()).as("weight 10 of 15").isEqualTo(0.67);
        assertThat(area(scores, AssessmentArea.PRACTICAL).score()).isEqualTo(50.0);
        assertThat(area(scores, AssessmentArea.PRACTICAL).lowConfidenceAnswers()).isEqualTo(1);
        assertThat(area(scores, AssessmentArea.PROJECT).shareOfOverall()).as("weight 2 of 15").isEqualTo(0.13);
        assertThat(area(scores, AssessmentArea.PROBLEM_SOLVING).score()).isNull();
        assertThat(area(scores, AssessmentArea.PROBLEM_SOLVING).evidenceCount()).isZero();
        assertThat(area(scores, AssessmentArea.PROBLEM_SOLVING).confidence()).isNull();

        ReportScores.AreaResult communication = area(scores, AssessmentArea.COMMUNICATION);
        assertThat(communication.score()).as("ratings 3, 4, 2, 3, 3, 4: mean 3.17 of 4").isEqualTo(79.17);
        assertThat(communication.evidenceCount()).isEqualTo(6);
        assertThat(communication.shareOfOverall()).isNull();
        assertThat(scores.areas()).extracting(ReportScores.AreaResult::area).containsExactly(AssessmentArea.values());
    }

    @Test
    void aSystemDesignDiscussionCountsOnceInProblemSolvingWithItsLastScore() {
        ReportScores scores = ReportAggregator.aggregate(List.of(
                ReportAnswers.intro(1, 70),
                ReportAnswers.design(2, 4, 40),
                ReportAnswers.followUp(3, 2, Section.SYSTEM_DESIGN, null, 4, 70),
                ReportAnswers.coding(4, 3, 90)));

        ReportScores.AreaResult problemSolving = area(scores, AssessmentArea.PROBLEM_SOLVING);
        assertThat(problemSolving.evidenceCount()).isEqualTo(2);
        assertThat(problemSolving.score()).as("(70*4 + 90*3) / 7").isEqualTo(78.57);
        assertThat(problemSolving.shareOfOverall()).isEqualTo(1.0);
    }

    @Test
    void anInterviewWithNothingButTheIntroductionHasAreasWithoutScoresButStillACommunicationRating() {
        ReportScores scores = ReportAggregator.aggregate(List.of(intro(1, 2)));

        assertThat(scores.areas()).filteredOn(result -> result.area() != AssessmentArea.COMMUNICATION)
                .allSatisfy(result -> assertThat(result.score()).isNull());
        assertThat(area(scores, AssessmentArea.COMMUNICATION).score()).isEqualTo(50.0);
        assertThat(area(scores, AssessmentArea.COMMUNICATION).confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void anAnswerCountsInOneAreaOfItsQuestionsAndOnlyAScoredAnswerHasACommunicationRating() {
        assertThatThrownBy(() -> new ReportAnswer(id(2), id(2), 2, QuestionKind.TECHNICAL, Section.QUESTIONING, "java/collections", 3, false, 70.0,
                Set.of(), null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportAnswer(id(2), id(2), 2, QuestionKind.TECHNICAL, Section.QUESTIONING, "java/collections", 3, false, 70.0,
                Set.of(), null, AssessmentArea.COMMUNICATION, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportAnswer(id(2), id(2), 2, QuestionKind.TECHNICAL, Section.QUESTIONING, "java/collections", 3, true, null,
                Set.of(), null, AssessmentArea.FUNDAMENTALS, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the fundamentals cap ---------------------------------------------------------------------------------------

    private static ReportScores interview(double fundamentalsScore, int fundamentalsAnswers, double projectScore, int projectAnswers) {
        List<ReportAnswer> answers = new ArrayList<>(List.of(intro(1, 3)));
        int seq = 2;
        for (int i = 0; i < fundamentalsAnswers; i++, seq++) {
            answers.add(typed(seq, seq, QuestionType.CONCEPT, 3, fundamentalsScore, 3));
        }
        for (int i = 0; i < projectAnswers; i++, seq++) {
            answers.add(typed(seq, seq, QuestionType.EXPERIENCE, 3, projectScore, 4));
        }
        return ReportAggregator.aggregate(answers);
    }

    @Test
    void weakFundamentalsCapTheBandAtDevelopingWhateverTheProjectsLookLike() {
        ReportScores scores = interview(40, 2, 100, 6);

        assertThat(scores.overallScore()).isEqualTo(85.0);
        assertThat(RecommendationPolicy.recommend(scores)).isEqualTo(Recommendation.STRONG);
        RecommendationPolicy.Capped capped = RecommendationPolicy.recommendWithFundamentals(scores);
        assertThat(capped.recommendation()).isEqualTo(Recommendation.DEVELOPING);
        assertThat(capped.capReason()).contains("fundamentals scored 40 over 2 answers, below 45").contains("would give Strong");
    }

    @Test
    void theCapNeedsTwoFundamentalsAnswersAndWeakFundamentals() {
        assertThat(RecommendationPolicy.recommendWithFundamentals(interview(40, 1, 100, 6)))
                .as("one answer is not enough to call the fundamentals weak")
                .isEqualTo(new RecommendationPolicy.Capped(Recommendation.STRONG, null));
        assertThat(RecommendationPolicy.recommendWithFundamentals(interview(45, 2, 100, 6)).capReason()).as("45 is not weak").isNull();
        assertThat(RecommendationPolicy.recommendWithFundamentals(interview(30, 3, 40, 1)))
                .as("a band at or below Developing is never raised or changed")
                .isEqualTo(new RecommendationPolicy.Capped(Recommendation.NOT_YET, null));
    }
}
