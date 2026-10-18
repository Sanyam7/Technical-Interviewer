package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.engine.EngineFixtures.BAND;
import static com.techinterviewer.interview.engine.EngineFixtures.CONCURRENCY;
import static com.techinterviewer.interview.engine.EngineFixtures.INDEXING;
import static com.techinterviewer.interview.engine.EngineFixtures.T0;
import static com.techinterviewer.interview.engine.EngineFixtures.answer;
import static com.techinterviewer.interview.engine.EngineFixtures.coding;
import static com.techinterviewer.interview.engine.EngineFixtures.followUp;
import static com.techinterviewer.interview.engine.EngineFixtures.intro;
import static com.techinterviewer.interview.engine.EngineFixtures.keyPoints;
import static com.techinterviewer.interview.engine.EngineFixtures.question;
import static com.techinterviewer.interview.engine.EngineFixtures.scored;
import static com.techinterviewer.interview.engine.EngineFixtures.technical;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.AnswerSummary;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.Misconception;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TopicStat;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryUpdaterTest {

    private static InterviewMemory apply(InterviewMemory memory, AnsweredQuestion answered, EvaluationOutcome outcome) {
        return MemoryUpdater.apply(memory, answered, outcome, BAND);
    }

    @Test
    void theFirstScoredAnswerOfATopicSeedsItsStats() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(60));

        assertThat(memory.topicStats().get(CONCURRENCY)).isEqualTo(new TopicStat(1, 60, 3, 60));
    }

    @Test
    void theSecondScoreIsBlendedIntoTheMovingAverage() {
        InterviewMemory first = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(60));

        InterviewMemory second = apply(first, technical(3, CONCURRENCY, 3, T0), scored(80));

        // ema = 0.6 * 80 + 0.4 * 60 = 72
        assertThat(second.topicStats().get(CONCURRENCY)).isEqualTo(new TopicStat(2, 72, 3, 80));
    }

    @Test
    void theLevelRisesWhenTheAverageAndTheLastScoreAreHighAndFallsWhenTheAverageIsLow() {
        InterviewMemory high = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(90));
        InterviewMemory low = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(20));

        assertThat(high.topicStats().get(CONCURRENCY).level()).isEqualTo(4);
        assertThat(low.topicStats().get(CONCURRENCY).level()).isEqualTo(2);
    }

    @Test
    void theLevelAccumulatesAcrossAnswersAndStaysInTheBand() {
        InterviewMemory memory = InterviewMemory.empty();
        int[] expectedLevels = {4, 4, 4};
        for (int i = 0; i < 3; i++) {
            memory = apply(memory, technical(2 + i, CONCURRENCY, 3, T0), scored(95));
            assertThat(memory.topicStats().get(CONCURRENCY).level()).isEqualTo(expectedLevels[i]);
        }
        for (int i = 0; i < 4; i++) {
            memory = apply(memory, technical(10 + i, CONCURRENCY, 3, T0), scored(0));
        }
        assertThat(memory.topicStats().get(CONCURRENCY).level()).isEqualTo(2);
    }

    @Test
    void aScoredFollowUpUpdatesTheAverageButNotTheAskedCount() {
        InterviewMemory first = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(60));

        InterviewMemory second = apply(first, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0), scored(80));

        assertThat(second.topicStats().get(CONCURRENCY)).isEqualTo(new TopicStat(1, 72, 3, 80));
    }

    @Test
    void aScoredFollowUpOnAnUnseenTopicStartsTheTopicWithZeroRootQuestions() {
        InterviewMemory memory = apply(InterviewMemory.empty(), followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0), scored(80));

        assertThat(memory.topicStats().get(CONCURRENCY).asked()).isZero();
    }

    @Test
    void aPendingEvaluationCountsTheQuestionAsAskedButLearnsNothing() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), EvaluationOutcome.pending());

        assertThat(memory.topicStats().get(CONCURRENCY)).isEqualTo(new TopicStat(1, DifficultyAdapter.NEUTRAL_SCORE, 3, DifficultyAdapter.NEUTRAL_SCORE));
        assertThat(memory.answerSummaries()).isEmpty();
        assertThat(memory.candidateClaims()).isEmpty();
        assertThat(memory.flags()).isEmpty();
    }

    @Test
    void aPendingEvaluationKeepsTheAverageAndLevelOfAKnownTopic() {
        InterviewMemory known = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(90));

        InterviewMemory memory = apply(known, technical(3, CONCURRENCY, 4, T0), EvaluationOutcome.pending());

        assertThat(memory.topicStats().get(CONCURRENCY)).isEqualTo(new TopicStat(2, 90, 4, 90));
    }

    @Test
    void aPendingFollowUpChangesNoTopicStat() {
        InterviewMemory known = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(70));

        InterviewMemory memory = apply(known, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0), EvaluationOutcome.pending());

        assertThat(memory.topicStats()).isEqualTo(known.topicStats());
        assertThat(memory.answerSummaries()).hasSize(1);
    }

    @Test
    void aSkippedRootQuestionScoresZeroForItsTopicAndLeavesASummary() {
        InterviewMemory first = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(80));

        InterviewMemory memory = apply(first, technical(3, CONCURRENCY, 3, T0), EvaluationOutcome.skipped());

        // the 80 raised the level to 4; ema = 0.6 * 0 + 0.4 * 80 = 32 lowers it back to 3
        assertThat(memory.topicStats().get(CONCURRENCY)).isEqualTo(new TopicStat(2, 32, 3, 0));
        assertThat(memory.answerSummaries().getLast())
                .isEqualTo(new AnswerSummary(question(3), CONCURRENCY, MemoryUpdater.SKIPPED_SUMMARY, 0));
    }

    @Test
    void aSkippedRootOnAnUnseenTopicSeedsItWithZero() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, INDEXING, 3, T0), EvaluationOutcome.skipped());

        assertThat(memory.topicStats().get(INDEXING)).isEqualTo(new TopicStat(1, 0, 2, 0));
    }

    @Test
    void aSkippedFollowUpAddsNothingToTopicsOrSummaries() {
        InterviewMemory first = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(80));

        InterviewMemory memory = apply(first, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0), EvaluationOutcome.skipped());

        assertThat(memory.topicStats()).isEqualTo(first.topicStats());
        assertThat(memory.answerSummaries()).isEqualTo(first.answerSummaries());
    }

    @Test
    void aScoredAnswerLeavesItsSummaryWithQuestionTopicAndScore() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0),
                EvaluationOutcome.scored(61.5, "Knows volatile, not happens-before.", List.of(), List.of(), Set.of()));

        assertThat(memory.answerSummaries())
                .containsExactly(new AnswerSummary(question(2), CONCURRENCY, "Knows volatile, not happens-before.", 61.5));
    }

    @Test
    void theIntroductionHasASummaryAndNoTopicStat() {
        InterviewMemory memory = apply(InterviewMemory.empty(), intro(T0), scored(70));

        assertThat(memory.topicStats()).isEmpty();
        assertThat(memory.answerSummaries()).containsExactly(new AnswerSummary(question(1), null, "Summary of the answer.", 70));
    }

    @Test
    void aSkippedIntroductionLeavesAZeroSummary() {
        InterviewMemory memory = apply(InterviewMemory.empty(), intro(T0), EvaluationOutcome.skipped());

        assertThat(memory.answerSummaries()).containsExactly(new AnswerSummary(question(1), null, MemoryUpdater.SKIPPED_SUMMARY, 0));
    }

    @Test
    void summariesAreKeptOldestFirst() {
        InterviewMemory memory = apply(InterviewMemory.empty(), intro(T0), scored(70));
        memory = apply(memory, technical(2, CONCURRENCY, 3, T0), scored(60));
        memory = apply(memory, technical(3, INDEXING, 3, T0), scored(50));

        assertThat(memory.answerSummaries()).extracting(AnswerSummary::questionId).containsExactly(question(1), question(2), question(3));
    }

    @Test
    void newClaimsAreRememberedUnprobedWithTheirSourceAnswer() {
        InterviewMemory memory = apply(InterviewMemory.empty(), intro(T0),
                scored(70, List.of(), List.of("Migrated a monolith to Kafka", "Led a team of five"), Set.of()));

        assertThat(memory.candidateClaims()).containsExactly(
                new Claim("Migrated a monolith to Kafka", false, answer(1)), new Claim("Led a team of five", false, answer(1)));
    }

    @Test
    void aClaimAlreadyKnownIsNotAddedAgainWhateverItsCaseAndWhitespace() {
        InterviewMemory first = apply(InterviewMemory.empty(), intro(T0), scored(70, List.of(), List.of("Used Kafka in production"), Set.of()));

        InterviewMemory second = apply(first, technical(2, CONCURRENCY, 3, T0),
                scored(70, List.of(), List.of("  used kafka in PRODUCTION ", "Knows Redis", "knows redis"), Set.of()));

        assertThat(second.candidateClaims()).extracting(Claim::text).containsExactly("Used Kafka in production", "Knows Redis");
        assertThat(second.candidateClaims().get(1).sourceAnswerId()).isEqualTo(answer(2));
    }

    @Test
    void anAlreadyProbedClaimStaysProbedWhenTheSameClaimIsMadeAgain() {
        InterviewMemory first = apply(InterviewMemory.empty(), intro(T0), scored(70, List.of(), List.of("Used Kafka"), Set.of()));
        InterviewMemory probed = MemoryUpdater.markClaimProbed(first, "Used Kafka");

        InterviewMemory second = apply(probed, technical(2, CONCURRENCY, 3, T0), scored(70, List.of(), List.of("Used Kafka"), Set.of()));

        assertThat(second.candidateClaims()).containsExactly(new Claim("Used Kafka", true, answer(1)));
    }

    @Test
    void markClaimProbedMarksTheFirstUnprobedClaimWithThatText() {
        InterviewMemory memory = new InterviewMemory(Map.of(), List.of(), List.of(
                new Claim("A", true, answer(1)), new Claim("B", false, answer(1)), new Claim("A", false, answer(2))),
                List.of(), Map.of(), Map.of(), null);

        InterviewMemory probed = MemoryUpdater.markClaimProbed(memory, "A");

        assertThat(probed.candidateClaims()).extracting(Claim::probed).containsExactly(true, false, true);
        assertThat(memory.candidateClaims()).extracting(Claim::probed).as("the original is untouched").containsExactly(true, false, false);
    }

    @Test
    void markClaimProbedKeepsTheRestOfTheMemoryIntact() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0),
                scored(70, keyPoints("kp1", KeyPointStatus.COVERED), List.of("Used Kafka"), Set.of(EvaluationFlag.OFF_TOPIC)))
                .withPendingStep(PendingStep.nextTopic(Section.QUESTIONING, INDEXING, 3));

        InterviewMemory probed = MemoryUpdater.markClaimProbed(memory, "Used Kafka");

        assertThat(memory.overall()).isNotNull();
        assertThat(probed).isEqualTo(new InterviewMemory(memory.topicStats(), memory.demonstratedKeyPoints(),
                List.of(new Claim("Used Kafka", true, answer(2))), memory.answerSummaries(), memory.flags(), memory.sectionProgress(),
                memory.pendingStep(), memory.overall(), memory.misconceptions()));
    }

    @Test
    void markClaimProbedRejectsAClaimThatIsUnknownOrAlreadyProbed() {
        InterviewMemory memory = new InterviewMemory(Map.of(), List.of(), List.of(new Claim("A", true, answer(1))), List.of(), Map.of(),
                Map.of(), null);

        assertThatThrownBy(() -> MemoryUpdater.markClaimProbed(memory, "A")).hasMessageContaining("no unprobed claim");
        assertThatThrownBy(() -> MemoryUpdater.markClaimProbed(memory, "B")).hasMessageContaining("no unprobed claim");
    }

    @Test
    void coveredKeyPointsAreRememberedAsDemonstratedPerTopicWithoutDuplicates() {
        InterviewMemory first = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0, "kp1", "kp2", "kp3"),
                scored(70, keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.PARTIAL, "kp3", KeyPointStatus.MISSING),
                        List.of(), Set.of()));

        InterviewMemory second = apply(first, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0, "kp1", "kp2"),
                scored(70, keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.COVERED), List.of(), Set.of()));

        assertThat(first.demonstratedKeyPoints()).containsExactly("java/concurrency:kp1");
        assertThat(second.demonstratedKeyPoints()).containsExactly("java/concurrency:kp1", "java/concurrency:kp2");
    }

    @Test
    void keyPointsOfATopicLessQuestionAreScopedByTheirSection() {
        InterviewMemory memory = apply(InterviewMemory.empty(), coding(2, 3, T0, "kp1"),
                scored(70, keyPoints("kp1", KeyPointStatus.COVERED), List.of(), Set.of()));

        assertThat(memory.demonstratedKeyPoints()).containsExactly("CODING:kp1");
    }

    @Test
    void flagsAreCountedAcrossAnswers() {
        InterviewMemory first = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0),
                scored(70, List.of(), List.of(), Set.of(EvaluationFlag.OFF_TOPIC, EvaluationFlag.INJECTION_SUSPECTED)));
        InterviewMemory second = apply(first, technical(3, INDEXING, 3, T0),
                scored(70, List.of(), List.of(), Set.of(EvaluationFlag.OFF_TOPIC)));

        assertThat(second.flags()).containsEntry("OFF_TOPIC", 2).containsEntry("INJECTION_SUSPECTED", 1).hasSize(2);
    }

    @Test
    void theFirstAnswerOfASectionRecordsWhenItsFirstQuestionWasAskedAndUsesASlot() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(70));

        assertThat(memory.sectionProgress()).containsExactly(Map.entry(Section.QUESTIONING, new SectionProgress(1, T0)));
    }

    @Test
    void laterAnswersOfTheSectionKeepTheStartAndRootQuestionsUseOneSlotEach() {
        InterviewMemory first = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(70));
        InterviewMemory second = apply(first, technical(3, INDEXING, 3, T0.plus(Duration.ofMinutes(4))), scored(70));

        assertThat(second.sectionProgress().get(Section.QUESTIONING)).isEqualTo(new SectionProgress(2, T0));
    }

    @Test
    void followUpsUseNoSlot() {
        InterviewMemory first = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(70));
        InterviewMemory second = apply(first, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0.plusSeconds(60)), scored(70));

        assertThat(second.sectionProgress().get(Section.QUESTIONING)).isEqualTo(new SectionProgress(1, T0));
    }

    @Test
    void everyOutcomeUsesTheSlotOfItsRootQuestionAndRecordsTheSectionStart() {
        for (EvaluationOutcome outcome : List.of(scored(70), EvaluationOutcome.pending(), EvaluationOutcome.skipped())) {
            InterviewMemory memory = apply(InterviewMemory.empty(), coding(2, 3, T0), outcome);

            assertThat(memory.sectionProgress()).as(outcome.status().name()).containsExactly(Map.entry(Section.CODING, new SectionProgress(1, T0)));
        }
    }

    @Test
    void aSkippedFollowUpStillRecordsTheSectionStartWithoutASlot() {
        InterviewMemory memory = apply(InterviewMemory.empty(),
                followUp(3, Section.CODING, null, 3, 1, T0), EvaluationOutcome.skipped());

        assertThat(memory.sectionProgress()).containsExactly(Map.entry(Section.CODING, new SectionProgress(0, T0)));
    }

    @Test
    void theAnswerConsumesThePendingStep() {
        InterviewMemory waiting = InterviewMemory.empty().withPendingStep(PendingStep.nextTopic(Section.QUESTIONING, CONCURRENCY, 3));

        assertThat(apply(waiting, technical(2, CONCURRENCY, 3, T0), scored(70)).pendingStep()).isNull();
        assertThat(apply(waiting, technical(2, CONCURRENCY, 3, T0), EvaluationOutcome.pending()).pendingStep()).isNull();
    }

    @Test
    void applyDoesNotModifyTheMemoryItWasGiven() {
        InterviewMemory before = apply(InterviewMemory.empty(), intro(T0), scored(70));
        InterviewMemory snapshot = new InterviewMemory(before.topicStats(), before.demonstratedKeyPoints(), before.candidateClaims(),
                before.answerSummaries(), before.flags(), before.sectionProgress(), before.pendingStep());

        apply(before, technical(2, CONCURRENCY, 3, T0), scored(60, keyPoints("kp1", KeyPointStatus.COVERED), List.of("c"),
                Set.of(EvaluationFlag.OFF_TOPIC)));

        assertThat(before).isEqualTo(snapshot);
    }

    // ---- the candidate as a whole --------------------------------------------------------------------------------------

    @Test
    void everyTechnicalAnswerMovesTheCandidatesOverallStatAcrossTopicsButTheIntroductionDoesNot() {
        InterviewMemory memory = apply(InterviewMemory.empty(), intro(T0), scored(95));
        assertThat(memory.overall()).as("the introduction measures communication, not the level").isNull();

        memory = apply(memory, technical(2, CONCURRENCY, 3, T0), scored(90));
        assertThat(memory.overall()).isEqualTo(new TopicStat(1, 90, 4, 90));
        memory = apply(memory, technical(3, INDEXING, 4, T0), scored(80));
        assertThat(memory.overall()).as("0.6 * 80 + 0.4 * 90 = 84, the band's top").isEqualTo(new TopicStat(2, 84, 4, 80));
        memory = apply(memory, followUp(4, Section.QUESTIONING, INDEXING, 4, 1, T0), scored(10));
        assertThat(memory.overall()).as("a follow-up counts too: 0.6 * 10 + 0.4 * 84 = 39.6").isEqualTo(new TopicStat(3, 39.6, 3, 10));
        assertThat(memory.currentLevel(BAND.start())).isEqualTo(3);
    }

    @Test
    void aSkippedMainQuestionCountsAsZeroOverallAndAPendingEvaluationOrSkippedFollowUpAsNothing() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), EvaluationOutcome.skipped());
        assertThat(memory.overall()).isEqualTo(new TopicStat(1, 0, 2, 0));

        InterviewMemory pending = apply(memory, technical(3, INDEXING, 2, T0), EvaluationOutcome.pending());
        assertThat(pending.overall()).isEqualTo(memory.overall());
        InterviewMemory skippedFollowUp = apply(memory, followUp(4, Section.QUESTIONING, CONCURRENCY, 2, 1, T0), EvaluationOutcome.skipped());
        assertThat(skippedFollowUp.overall()).isEqualTo(memory.overall());
    }

    @Test
    void aCodingAnswerWithoutATopicStillMovesTheOverallLevel() {
        InterviewMemory memory = apply(InterviewMemory.empty(), coding(2, 3, T0), scored(88));

        assertThat(memory.topicStats()).isEmpty();
        assertThat(memory.overall()).isEqualTo(new TopicStat(1, 88, 4, 88));
    }

    @Test
    void aNewTopicsLevelMovesFromTheLevelItsQuestionWasAskedAt() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 4, T0), scored(90));
        InterviewMemory pending = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 2, T0), EvaluationOutcome.pending());

        assertThat(memory.topicStats().get(CONCURRENCY).level()).as("asked at 4, raised, clamped to the band's 4").isEqualTo(4);
        assertThat(pending.topicStats().get(CONCURRENCY))
                .isEqualTo(new TopicStat(1, DifficultyAdapter.NEUTRAL_SCORE, 2, DifficultyAdapter.NEUTRAL_SCORE));
    }

    @Test
    void misconceptionsAreTheFactualErrorsOfScoredAnswersWithTheirTopicWithoutDuplicatesTheLatestTen() {
        InterviewMemory memory = apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0),
                EvaluationOutcome.scored(30, "s", List.of(), List.of(), Set.of(), List.of("volatile makes count++ atomic")));
        memory = apply(memory, coding(3, 3, T0),
                EvaluationOutcome.scored(30, "s", List.of(), List.of(), Set.of(), List.of(" Volatile makes COUNT++ atomic ", "x".repeat(400))));

        assertThat(memory.misconceptions()).hasSize(2);
        assertThat(memory.misconceptions().getFirst()).isEqualTo(new Misconception("volatile makes count++ atomic", CONCURRENCY));
        assertThat(memory.misconceptions().get(1).topic()).isNull();
        assertThat(memory.misconceptions().get(1).claim()).hasSize(Misconception.MAX_LENGTH).endsWith("...");

        for (int i = 0; i < 12; i++) {
            memory = apply(memory, technical(10 + i, INDEXING, 3, T0),
                    EvaluationOutcome.scored(40, "s", List.of(), List.of(), Set.of(), List.of("error " + i)));
        }
        assertThat(memory.misconceptions()).hasSize(InterviewMemory.MAX_MISCONCEPTIONS);
        assertThat(memory.misconceptions().getLast().claim()).isEqualTo("error 11");
        assertThat(memory.misconceptions().getFirst().claim()).isEqualTo("error 2");
    }

    @Test
    void aRepeatedAnswerIsCountedUnderItsOwnFlag() {
        InterviewMemory memory = MemoryUpdater.apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0), scored(40), BAND, true);
        memory = MemoryUpdater.apply(memory, technical(3, INDEXING, 3, T0), EvaluationOutcome.pending(), BAND, true);
        memory = MemoryUpdater.apply(memory, technical(4, INDEXING, 3, T0), scored(40), BAND, false);

        assertThat(memory.flags()).isEqualTo(Map.of(MemoryUpdater.REPEATED_ANSWER_FLAG, 2));
    }

    @Test
    void outcomeCarriesDataOnlyWhenScored() {
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Status.PENDING, 50.0, null, List.of(), List.of(), Set.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Status.SKIPPED, null, "s", List.of(), List.of(), Set.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Status.PENDING, null, null, List.of(), List.of("c"), Set.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Status.SKIPPED, null, null,
                List.of(EngineFixtures.keyPoint("k", KeyPointStatus.COVERED)), List.of(), Set.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Status.PENDING, null, null, List.of(), List.of(),
                Set.of(EvaluationFlag.OFF_TOPIC), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Status.PENDING, null, null, List.of(), List.of(), Set.of(),
                List.of("volatile makes count++ atomic"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationOutcome.scored(50, "s", List.of(), List.of(), Set.of(), List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationOutcome.scored(100.01, "s", List.of(), List.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationOutcome.scored(-0.01, "s", List.of(), List.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationOutcome.scored(50, " ", List.of(), List.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationOutcome.scored(50, "s", List.of(), List.of(" "), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Status.SCORED, null, "s", List.of(), List.of(), Set.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EvaluationOutcome.scored(0, "s", List.of(), List.of(), Set.of()).score()).isEqualTo(0.0);
        assertThat(EvaluationOutcome.scored(100, "s", List.of(), List.of(), Set.of()).score()).isEqualTo(100.0);
        assertThat(EvaluationOutcome.pending().status()).isEqualTo(EvaluationOutcome.Status.PENDING);
        assertThat(EvaluationOutcome.skipped().status()).isEqualTo(EvaluationOutcome.Status.SKIPPED);
    }

    @Test
    void anEvaluationInDoubtCountsInTheAveragesButMovesNeitherTheTopicNorTheCandidate() {
        EvaluationOutcome doubted = scored(10).unreliable();

        InterviewMemory updated = MemoryUpdater.apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0, "kp1"), doubted, BAND);

        assertThat(updated.topicStats().get(CONCURRENCY)).isEqualTo(new TopicStat(1, 10, 3, 10));
        assertThat(updated.overall()).isEqualTo(new TopicStat(1, 10, 3, 10));
        assertThat(MemoryUpdater.apply(InterviewMemory.empty(), technical(2, CONCURRENCY, 3, T0, "kp1"), scored(10), BAND).overall().level())
                .as("the same score from a reliable evaluation lowers the level").isEqualTo(2);
    }

    @Test
    void onlyAScoredOutcomeCanBeInDoubt() {
        assertThatThrownBy(() -> EvaluationOutcome.pending().unreliable()).isInstanceOf(IllegalStateException.class);
        assertThat(EvaluationOutcome.skipped().reliable()).isTrue();
    }

    @Test
    void theStagesDecidedAreKeptWhenAnAnswerIsApplied() {
        InterviewMemory memory = InterviewMemory.empty().withStageQuestion(com.techinterviewer.interview.domain.InterviewStage.FUNDAMENTALS);

        InterviewMemory updated = MemoryUpdater.apply(memory, technical(2, CONCURRENCY, 3, T0, "kp1"), scored(60), BAND);

        assertThat(updated.stageQuestionsOf(com.techinterviewer.interview.domain.InterviewStage.FUNDAMENTALS)).isEqualTo(1);
    }
}
