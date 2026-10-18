package com.techinterviewer.evaluation;

import static com.techinterviewer.evaluation.ReportAnswers.coding;
import static com.techinterviewer.evaluation.ReportAnswers.design;
import static com.techinterviewer.evaluation.ReportAnswers.followUp;
import static com.techinterviewer.evaluation.ReportAnswers.id;
import static com.techinterviewer.evaluation.ReportAnswers.intro;
import static com.techinterviewer.evaluation.ReportAnswers.skippedCoding;
import static com.techinterviewer.evaluation.ReportAnswers.skippedDesign;
import static com.techinterviewer.evaluation.ReportAnswers.skippedFollowUp;
import static com.techinterviewer.evaluation.ReportAnswers.skippedIntro;
import static com.techinterviewer.evaluation.ReportAnswers.skippedTechnical;
import static com.techinterviewer.evaluation.ReportAnswers.technical;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.ReportScores.SectionScore;
import com.techinterviewer.evaluation.ReportScores.SkillScore;
import com.techinterviewer.evaluation.ReportScores.TopicScore;
import com.techinterviewer.evaluation.domain.Confidence;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReportAggregatorTest {

    private static final String JAVA = "java/concurrency";
    private static final String SQL = "postgresql/indexing";

    private static ReportScores aggregate(ReportAnswer... answers) {
        return ReportAggregator.aggregate(List.of(answers));
    }

    @Test
    void anInterviewWithoutAnswersHasInsufficientEvidence() {
        ReportScores scores = ReportAggregator.aggregate(List.of());

        assertThat(scores.insufficientEvidence()).isTrue();
        assertThat(scores.overallScore()).isNull();
        assertThat(scores.overallConfidence()).isEqualTo(Confidence.LOW);
        assertThat(scores.sections()).isEmpty();
        assertThat(scores.skills()).isEmpty();
        assertThat(scores.topics()).isEmpty();
        assertThat(scores.integrityNotes()).isEmpty();
        assertThat(scores.coverage()).isEqualTo(new ReportScores.Coverage(0, 0, 0, 0, 0));
    }

    @Test
    void onlyTheIntroductionAnsweredIsInsufficientButItsSectionIsStillScored() {
        ReportScores scores = aggregate(intro(1, 75));

        assertThat(scores.overallScore()).isNull();
        assertThat(scores.insufficientEvidence()).isTrue();
        assertThat(scores.overallConfidence()).isEqualTo(Confidence.LOW);
        assertThat(scores.sections()).containsExactly(new SectionScore(Section.INTRODUCTION, 75.0, 1));
        assertThat(scores.skills()).isEmpty();
        assertThat(scores.topics()).isEmpty();
        assertThat(scores.coverage().questionsAnswered()).isEqualTo(1);
    }

    @Test
    void everyQuestionSkippedIsInsufficientEvidenceToo() {
        ReportScores scores = aggregate(intro(1, 60), skippedTechnical(2, JAVA, 3), skippedCoding(3, 3), skippedDesign(4, 3));

        assertThat(scores.overallScore()).isNull();
        assertThat(scores.skills()).isEmpty();
        assertThat(scores.topics()).isEmpty();
        assertThat(scores.sections()).extracting(SectionScore::section).containsExactly(Section.INTRODUCTION);
        assertThat(scores.coverage().skipped()).isEqualTo(3);
        assertThat(scores.coverage().questionsAnswered()).isEqualTo(1);
    }

    @Test
    void overallIsWeightedByDifficultyLevel() {
        // (60 * 2 + 90 * 4) / 6 = 80
        ReportScores scores = aggregate(technical(1, JAVA, 2, 60), technical(2, JAVA, 4, 90));

        assertThat(scores.overallScore()).isEqualTo(80.0);
    }

    @Test
    void aScoreAtAHigherLevelCountsMoreThanTheSameScoreAtALowerLevel() {
        ReportScores strongWhenHard = aggregate(technical(1, JAVA, 2, 40), technical(2, JAVA, 4, 80));
        ReportScores strongWhenEasy = aggregate(technical(1, JAVA, 2, 80), technical(2, JAVA, 4, 40));

        assertThat(strongWhenHard.overallScore()).isEqualTo(66.67);
        assertThat(strongWhenEasy.overallScore()).isEqualTo(53.33);
    }

    @Test
    void theIntroductionIsNotTechnicalEvidence() {
        ReportScores scores = aggregate(intro(1, 100), technical(2, JAVA, 3, 50));

        assertThat(scores.overallScore()).isEqualTo(50.0);
        assertThat(scores.sections()).containsExactly(
                new SectionScore(Section.INTRODUCTION, 100.0, 1), new SectionScore(Section.QUESTIONING, 50.0, 1));
        assertThat(scores.overallConfidence()).as("one technical turn only").isEqualTo(Confidence.LOW);
    }

    @Test
    void aSkippedRootQuestionScoresZero() {
        ReportScores scores = aggregate(technical(1, JAVA, 3, 80), skippedTechnical(2, JAVA, 3));

        assertThat(scores.overallScore()).isEqualTo(40.0);
        assertThat(scores.topics()).containsExactly(new TopicScore(JAVA, 40.0, 2, 3.0));
        assertThat(scores.coverage().skipped()).isEqualTo(1);
    }

    @Test
    void aSkippedFollowUpAddsNothing() {
        ReportScores scores = aggregate(technical(1, JAVA, 3, 80), skippedFollowUp(2, 1, Section.QUESTIONING, JAVA, 3));

        assertThat(scores.overallScore()).isEqualTo(80.0);
        assertThat(scores.topics()).containsExactly(new TopicScore(JAVA, 80.0, 1, 3.0));
        assertThat(scores.coverage().skipped()).as("it is still reported as skipped").isEqualTo(1);
        assertThat(scores.coverage().followUps()).isEqualTo(1);
    }

    @Test
    void everyAnsweredFollowUpOfATechnicalThreadCountsOnItsOwn() {
        ReportScores scores = aggregate(
                technical(1, JAVA, 3, 80),
                followUp(2, 1, Section.QUESTIONING, JAVA, 3, 40),
                followUp(3, 1, Section.QUESTIONING, JAVA, 4, 60));

        // (80 * 3 + 40 * 3 + 60 * 4) / 10 = 60
        assertThat(scores.overallScore()).isEqualTo(60.0);
        assertThat(scores.topics()).containsExactly(new TopicScore(JAVA, 60.0, 3, 3.33));
    }

    @Test
    void aCodingFollowUpCountsOnItsOwnLikeATechnicalOne() {
        ReportScores scores = aggregate(coding(1, 3, 90), followUp(2, 1, Section.CODING, null, 3, 50), coding(3, 3, 70));

        assertThat(scores.sections()).containsExactly(new SectionScore(Section.CODING, 70.0, 3));
    }

    @Test
    void aSystemDesignThreadIsOneTurnWithTheScoreOfItsLastAnsweredAnswer() {
        ReportScores scores = aggregate(
                design(1, 3, 40),
                followUp(2, 1, Section.SYSTEM_DESIGN, null, 3, 55),
                followUp(3, 1, Section.SYSTEM_DESIGN, null, 3, 70));

        assertThat(scores.sections()).containsExactly(new SectionScore(Section.SYSTEM_DESIGN, 70.0, 1));
        assertThat(scores.overallScore()).isEqualTo(70.0);
    }

    @Test
    void aSkippedDesignFollowUpLeavesTheThreadAtItsLastAnsweredTurn() {
        ReportScores scores = aggregate(
                design(1, 3, 40),
                followUp(2, 1, Section.SYSTEM_DESIGN, null, 3, 65),
                skippedFollowUp(3, 1, Section.SYSTEM_DESIGN, null, 3));

        assertThat(scores.sections()).containsExactly(new SectionScore(Section.SYSTEM_DESIGN, 65.0, 1));
    }

    @Test
    void aDesignThreadKeepsTheScoreOfItsLastAnsweredTurnEvenWhenItIsLowerThanAnEarlierOne() {
        ReportScores scores = aggregate(design(1, 3, 90), followUp(2, 1, Section.SYSTEM_DESIGN, null, 3, 30));

        assertThat(scores.sections()).containsExactly(new SectionScore(Section.SYSTEM_DESIGN, 30.0, 1));
    }

    @Test
    void aDesignThreadWhereEverythingWasSkippedScoresZeroOnceItsSiblingsAreAnswered() {
        ReportScores scores = aggregate(technical(1, JAVA, 3, 80), skippedDesign(2, 3));

        assertThat(scores.sections()).containsExactly(
                new SectionScore(Section.QUESTIONING, 80.0, 1), new SectionScore(Section.SYSTEM_DESIGN, 0.0, 1));
        assertThat(scores.overallScore()).isEqualTo(40.0);
    }

    @Test
    void aDesignThreadCarriesTheDifficultyOfItsRootQuestion() {
        // follow-up asked at level 5 must not change the weight of the thread: (60 * 2 + 90 * 1) / 3 = 70
        ReportScores scores = aggregate(
                technical(1, JAVA, 1, 90),
                design(2, 2, 40),
                followUp(3, 2, Section.SYSTEM_DESIGN, null, 5, 60));

        assertThat(scores.overallScore()).isEqualTo(70.0);
    }

    @Test
    void sectionsAreListedInRunningOrderWhateverTheInputOrder() {
        List<ReportAnswer> answers = new ArrayList<>(List.of(
                intro(1, 80), technical(2, JAVA, 3, 60), coding(3, 3, 70), design(4, 3, 50)));
        Collections.shuffle(answers, new Random(7));

        ReportScores scores = ReportAggregator.aggregate(answers);

        assertThat(scores.sections()).extracting(SectionScore::section).containsExactly(
                Section.INTRODUCTION, Section.QUESTIONING, Section.CODING, Section.SYSTEM_DESIGN);
        assertThat(scores.sections()).extracting(SectionScore::score).containsExactly(80.0, 60.0, 70.0, 50.0);
        assertThat(scores.overallScore()).isEqualTo(60.0);
    }

    @Test
    void theResultDoesNotDependOnTheOrderOfTheInput() {
        List<ReportAnswer> answers = List.of(
                intro(1, 80), technical(2, JAVA, 3, 60), followUp(3, 2, Section.QUESTIONING, JAVA, 3, 50),
                technical(4, SQL, 2, 90), skippedTechnical(5, SQL, 2), coding(6, 3, 70), design(7, 4, 55),
                followUp(8, 7, Section.SYSTEM_DESIGN, null, 4, 65));
        ReportScores expected = ReportAggregator.aggregate(answers);

        for (long seed = 0; seed < 10; seed++) {
            List<ReportAnswer> shuffled = new ArrayList<>(answers);
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(ReportAggregator.aggregate(shuffled)).isEqualTo(expected);
        }
    }

    @Test
    void skillScoresGroupTheTopicsOfASkillAndAreOrderedBySlug() {
        ReportScores scores = aggregate(
                technical(1, SQL, 3, 50),
                technical(2, JAVA, 2, 60),
                technical(3, "java/collections", 4, 90),
                technical(4, JAVA, 3, 70));

        // java: (60 * 2 + 90 * 4 + 70 * 3) / 9 = 76.67 over 3 turns, avg level 3
        // postgresql: 50 over 1 turn
        assertThat(scores.skills()).containsExactly(
                new SkillScore("java", 76.67, Confidence.MEDIUM, 3, 3.0),
                new SkillScore("postgresql", 50.0, Confidence.LOW, 1, 3.0));
        assertThat(scores.topics()).containsExactly(
                new TopicScore("java/collections", 90.0, 1, 4.0),
                new TopicScore(JAVA, 66.0, 2, 2.5),
                new TopicScore(SQL, 50.0, 1, 3.0));
    }

    @Test
    void codingAndDesignWithoutATopicScoreTheirSectionButNoSkill() {
        ReportScores scores = aggregate(technical(1, JAVA, 3, 60), coding(2, 3, 90), design(3, 3, 30));

        assertThat(scores.skills()).extracting(SkillScore::skill).containsExactly("java");
        assertThat(scores.topics()).extracting(TopicScore::topic).containsExactly(JAVA);
        assertThat(scores.overallScore()).isEqualTo(60.0);
    }

    @Test
    void aCodingQuestionWithATopicCountsForItsSkill() {
        ReportAnswer codingWithTopic = new ReportAnswer(
                id(2), id(2), 2, QuestionKind.CODING, Section.CODING, "java/streams", 3, false, 90.0,
                Set.of());

        ReportScores scores = aggregate(technical(1, JAVA, 3, 60), codingWithTopic);

        assertThat(scores.skills()).containsExactly(new SkillScore("java", 75.0, Confidence.MEDIUM, 2, 3.0));
    }

    @Test
    void skippedRootQuestionsCountTowardsTheSkillScoreAsZero() {
        ReportScores scores = aggregate(technical(1, JAVA, 3, 90), skippedTechnical(2, SQL, 3));

        assertThat(scores.skills()).containsExactly(
                new SkillScore("java", 90.0, Confidence.LOW, 1, 3.0),
                new SkillScore("postgresql", 0.0, Confidence.LOW, 1, 3.0));
    }

    @ParameterizedTest
    @CsvSource({
        // turns, best, worst, expected confidence
        "1, 70, 70, LOW",
        "2, 70, 70, MEDIUM",
        "3, 70, 70, MEDIUM",
        "4, 70, 70, HIGH",
        "6, 70, 70, HIGH",
        "4, 90, 50, HIGH",
        "4, 90, 49.99, MEDIUM",
        "2, 90, 50, MEDIUM",
        "2, 90, 49.99, LOW",
        "3, 100, 0, LOW",
        "6, 100, 0, MEDIUM",
        "1, 70, 70, LOW"
    })
    void confidenceDependsOnTheNumberOfTurnsAndTheirSpread(int turns, double best, double worst, Confidence expected) {
        List<ReportAnswer> answers = new ArrayList<>();
        answers.add(technical(1, JAVA, 3, best));
        for (int i = 1; i < turns; i++) {
            answers.add(technical(i + 1, JAVA, 3, i == 1 ? worst : best));
        }

        ReportScores scores = ReportAggregator.aggregate(answers);

        assertThat(scores.skills().getFirst().confidence()).isEqualTo(expected);
        assertThat(scores.overallConfidence()).isEqualTo(expected);
    }

    @Test
    void overallConfidenceLooksAtAllTechnicalTurnsAcrossSkills() {
        ReportScores scores = aggregate(
                technical(1, JAVA, 3, 70), technical(2, SQL, 3, 70), coding(3, 3, 70), design(4, 3, 70));

        assertThat(scores.overallConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(scores.skills()).extracting(SkillScore::confidence).containsOnly(Confidence.LOW);
    }

    @Test
    void scoresRoundHalfUpToTwoDecimals() {
        // (10.00 + 10.01) / 2 = 10.005 exactly: half up gives 10.01, half even would give 10.00
        ReportScores scores = aggregate(technical(1, JAVA, 3, 10.00), technical(2, JAVA, 3, 10.01));

        assertThat(scores.overallScore()).isEqualTo(10.01);
        assertThat(scores.topics().getFirst().score()).isEqualTo(10.01);
        assertThat(scores.skills().getFirst().score()).isEqualTo(10.01);
        assertThat(scores.sections().getFirst().score()).isEqualTo(10.01);
    }

    @Test
    void averageDifficultyRoundsHalfUpToTwoDecimals() {
        // levels 1 x7 and 2 x1 over 8 turns: 9 / 8 = 1.125 -> 1.13
        List<ReportAnswer> answers = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            answers.add(technical(i, JAVA, 1, 50));
        }
        answers.add(technical(8, JAVA, 2, 50));

        assertThat(ReportAggregator.aggregate(answers).skills().getFirst().averageDifficulty()).isEqualTo(1.13);
    }

    @Test
    void coverageCountsAnsweredSkippedFollowUpsTopicsAndSkills() {
        ReportScores scores = aggregate(
                intro(1, 70),
                technical(2, JAVA, 3, 60),
                followUp(3, 2, Section.QUESTIONING, JAVA, 3, 50),
                skippedFollowUp(4, 2, Section.QUESTIONING, JAVA, 3),
                technical(5, "java/collections", 3, 60),
                technical(6, SQL, 3, 60),
                skippedTechnical(7, "redis/caching", 3),
                coding(8, 3, 60));

        assertThat(scores.coverage()).isEqualTo(new ReportScores.Coverage(6, 2, 2, 3, 2));
    }

    @Test
    void integrityNotesCountFlaggedAnswersInFlagOrder() {
        ReportScores scores = aggregate(
                intro(1, 70),
                technical(2, JAVA, 3, 60, EvaluationFlag.OFF_TOPIC),
                technical(3, JAVA, 3, 60, EvaluationFlag.INJECTION_SUSPECTED, EvaluationFlag.OFF_TOPIC),
                coding(4, 3, 60, EvaluationFlag.LANGUAGE_MISMATCH),
                technical(5, JAVA, 3, 60));

        assertThat(scores.integrityNotes()).extracting(ReportScores.IntegrityNote::flag).containsExactly(
                EvaluationFlag.INJECTION_SUSPECTED, EvaluationFlag.OFF_TOPIC, EvaluationFlag.LANGUAGE_MISMATCH);
        assertThat(scores.integrityNotes()).extracting(ReportScores.IntegrityNote::answerCount).containsExactly(1, 2, 1);
        assertThat(scores.integrityNotes().get(1).questionIds()).containsExactly(id(2), id(3));
        assertThat(scores.integrityNotes()).extracting(ReportScores.IntegrityNote::message).doesNotHaveDuplicates().allMatch(m -> !m.isBlank());
    }

    @Test
    void everyFlagHasAnIntegrityMessage() {
        for (EvaluationFlag flag : EvaluationFlag.values()) {
            ReportScores scores = aggregate(technical(1, JAVA, 3, 60, flag));

            assertThat(scores.integrityNotes()).as(flag.name()).hasSize(1);
            assertThat(scores.integrityNotes().getFirst().message()).isNotBlank();
        }
    }

    @Test
    void flagsAreReportedEvenWithoutEnoughEvidenceForAScore() {
        ReportScores scores = aggregate(
                new ReportAnswer(id(1), id(1), 1, QuestionKind.INTRO, Section.INTRODUCTION, null, 3,
                        false, 40.0, Set.of(EvaluationFlag.INJECTION_SUSPECTED)));

        assertThat(scores.overallScore()).isNull();
        assertThat(scores.integrityNotes()).hasSize(1);
    }

    @Test
    void aSkippedIntroductionScoresZeroInItsSectionAndIsNotAnAnswer() {
        ReportScores scores = aggregate(skippedIntro(1), technical(2, JAVA, 3, 60));

        assertThat(scores.sections().getFirst()).isEqualTo(new SectionScore(Section.INTRODUCTION, 0.0, 1));
        assertThat(scores.coverage().questionsAnswered()).isEqualTo(1);
        assertThat(scores.coverage().skipped()).isEqualTo(1);
        assertThat(scores.overallScore()).isEqualTo(60.0);
    }

    @Test
    void contradictoryAnswersAreRejected() {
        assertThatThrownBy(() -> aggregate(technical(1, JAVA, 3, 60), technical(1, JAVA, 3, 60)))
                .hasMessageContaining("appears twice");
        ReportAnswer sameSequence = new ReportAnswer(id(9), id(9), 1, QuestionKind.TECHNICAL,
                Section.QUESTIONING, JAVA, 3, false, 60.0, Set.of());
        assertThatThrownBy(() -> aggregate(technical(1, JAVA, 3, 60), sameSequence))
                .hasMessageContaining("sequence number 1 appears twice");
    }

    @Test
    void aFollowUpWithoutAValidRootIsRejected() {
        assertThatThrownBy(() -> aggregate(followUp(2, 1, Section.QUESTIONING, JAVA, 3, 50)))
                .as("root missing").hasMessageContaining("needs a root question");
        assertThatThrownBy(() -> aggregate(technical(1, JAVA, 3, 60), followUp(2, 1, Section.CODING, null, 3, 50)))
                .as("root in another section").hasMessageContaining("needs a root question");
        assertThatThrownBy(() -> aggregate(technical(3, JAVA, 3, 60), followUp(2, 3, Section.QUESTIONING, JAVA, 3, 50)))
                .as("root asked after the follow-up").hasMessageContaining("needs a root question");
        assertThatThrownBy(() -> aggregate(
                technical(1, JAVA, 3, 60),
                followUp(2, 1, Section.QUESTIONING, JAVA, 3, 50),
                followUp(3, 2, Section.QUESTIONING, JAVA, 3, 50)))
                .as("the thread id must be the root, not another follow-up").hasMessageContaining("needs a root question");
    }

    @Test
    void aFollowUpAskedImmediatelyAfterItsRootIsAccepted() {
        assertThat(aggregate(technical(1, JAVA, 3, 60), followUp(2, 1, Section.QUESTIONING, JAVA, 3, 50)).overallScore())
                .isEqualTo(55.0);
    }

    @Test
    void nullAnswersAreRejected() {
        assertThatThrownBy(() -> ReportAggregator.aggregate(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
