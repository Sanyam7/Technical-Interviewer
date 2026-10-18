package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.engine.EngineFixtures.BAND;
import static com.techinterviewer.interview.engine.EngineFixtures.CONCURRENCY;
import static com.techinterviewer.interview.engine.EngineFixtures.INDEXING;
import static com.techinterviewer.interview.engine.EngineFixtures.T0;
import static com.techinterviewer.interview.engine.EngineFixtures.answer;
import static com.techinterviewer.interview.engine.EngineFixtures.coding;
import static com.techinterviewer.interview.engine.EngineFixtures.design;
import static com.techinterviewer.interview.engine.EngineFixtures.followUp;
import static com.techinterviewer.interview.engine.EngineFixtures.intro;
import static com.techinterviewer.interview.engine.EngineFixtures.keyPoints;
import static com.techinterviewer.interview.engine.EngineFixtures.question;
import static com.techinterviewer.interview.engine.EngineFixtures.scored;
import static com.techinterviewer.interview.engine.EngineFixtures.technical;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TopicStat;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TurnDeciderTest {

    private static final Instant DEADLINE = T0.plus(Duration.ofMinutes(60));
    private static final Instant NOW = T0.plus(Duration.ofMinutes(5));

    /** Collects the facts of a turn with sensible defaults: a technical root answered in QUESTIONING, five minutes in. */
    private static final class Turn {

        InterviewState state = InterviewState.QUESTIONING;
        Section resume;
        InterviewPlan plan = EngineFixtures.fullLoop();
        InterviewMemory memory = InterviewMemory.empty();
        Map<String, TopicRange> ranges = EngineFixtures.ranges();
        AnsweredQuestion answered = technical(2, CONCURRENCY, 3, T0, "kp1", "kp2");
        EvaluationOutcome outcome = scored(60, keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.COVERED), List.of(), Set.of());
        Instant now = NOW;
        Instant deadline = DEADLINE;
        boolean endRequested;
        Set<Criterion> probed = Set.of();

        Turn probed(Criterion... criteria) {
            this.probed = Set.of(criteria);
            return this;
        }

        Turn state(InterviewState state) {
            this.state = state;
            return this;
        }

        Turn resume(Section resume) {
            this.resume = resume;
            return this;
        }

        Turn plan(InterviewPlan plan) {
            this.plan = plan;
            return this;
        }

        Turn memory(InterviewMemory memory) {
            this.memory = memory;
            return this;
        }

        Turn ranges(Map<String, TopicRange> ranges) {
            this.ranges = ranges;
            return this;
        }

        Turn answered(AnsweredQuestion answered) {
            this.answered = answered;
            return this;
        }

        Turn outcome(EvaluationOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        Turn now(Instant now) {
            this.now = now;
            return this;
        }

        Turn endRequested() {
            this.endRequested = true;
            return this;
        }

        TurnDecision decide() {
            return TurnDecider.decide(new TurnInput(state, resume, plan, memory, ranges, answered, outcome, now, deadline, endRequested, false,
                    probed));
        }
    }

    private static InterviewMemory memory(Map<Section, SectionProgress> progress, Map<String, TopicStat> stats, List<Claim> claims) {
        return new InterviewMemory(stats, List.of(), claims, List.of(), Map.of(), progress, null);
    }

    private static Map<Section, SectionProgress> questioningProgress(int slotsUsed) {
        Map<Section, SectionProgress> progress = new LinkedHashMap<>();
        progress.put(Section.INTRODUCTION, new SectionProgress(1, T0));
        progress.put(Section.QUESTIONING, new SectionProgress(slotsUsed, T0));
        return progress;
    }

    private static TopicStat stat(int asked, double ema, int level, double last) {
        return new TopicStat(asked, ema, level, last);
    }

    // ---- introduction ----------------------------------------------------------------------------------------------

    @Test
    void answeringTheIntroductionMovesToTheFirstTopicQuestion() {
        TurnDecision decision = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0))
                .outcome(scored(70, List.of(), List.of("Used Kafka in production"), Set.of())).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.INTRO_ANSWERED);
        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.resumeSection()).isNull();
        assertThat(decision.endReason()).isNull();
        assertThat(decision.interviewComplete()).isFalse();
        assertThat(decision.transitions()).hasSize(1);
        assertThat(decision.pendingStep()).isEqualTo(PendingStep.nextTopic(Section.QUESTIONING, CONCURRENCY, 3));
        assertThat(decision.memory().pendingStep()).isEqualTo(decision.pendingStep());
        assertThat(decision.memory().candidateClaims()).extracting(Claim::text, Claim::probed)
                .containsExactly(tuple("Used Kafka in production", false));
        assertThat(decision.memory().answerSummaries()).hasSize(1);
        assertThat(decision.memory().sectionProgress().get(Section.INTRODUCTION)).isEqualTo(new SectionProgress(1, T0));
    }

    @Test
    void theIntroductionNeverFollowsUpEvenWithAGapOrAClaim() {
        TurnDecision decision = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0))
                .outcome(scored(60, keyPoints("kp1", KeyPointStatus.MISSING), List.of("Used Kafka"), Set.of())).decide();

        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.pendingStep().kind()).isEqualTo(PendingStep.Kind.NEXT_TOPIC);
    }

    @Test
    void pendingOrSkippedIntroductionsMoveOnToo() {
        for (EvaluationOutcome outcome : List.of(EvaluationOutcome.pending(), EvaluationOutcome.skipped())) {
            TurnDecision decision = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).outcome(outcome).decide();

            assertThat(decision.nextState()).as(outcome.status().name()).isEqualTo(InterviewState.QUESTIONING);
        }
    }

    @Test
    void theIntroductionLeadsIntoWhicheverSectionThePlanRunsFirst() {
        TurnDecision coding = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).plan(EngineFixtures.codingOnly()).decide();
        TurnDecision design = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).plan(EngineFixtures.designOnly()).decide();

        assertThat(coding.nextState()).isEqualTo(InterviewState.CODING);
        assertThat(coding.pendingStep()).isEqualTo(PendingStep.nextTopic(Section.CODING, null, 3));
        assertThat(design.nextState()).isEqualTo(InterviewState.SYSTEM_DESIGN);
        assertThat(design.pendingStep()).isEqualTo(PendingStep.nextTopic(Section.SYSTEM_DESIGN, null, 3));
    }

    @Test
    void aPlanWithNothingAfterTheIntroductionIsRejected() {
        InterviewPlan onlyIntro = new InterviewPlan(1, List.of(new SectionPlan(Section.INTRODUCTION, 4, 1)), BAND, 2);

        assertThatThrownBy(() -> new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).plan(onlyIntro).decide())
                .isInstanceOf(IllegalTransitionException.class).hasMessageContaining("no section after the introduction");
    }

    // ---- root question, follow-up decision ---------------------------------------------------------------------------

    @Test
    void aGapInAMiddlingAnswerLeadsToAFollowUpOnThatKeyPoint() {
        TurnDecision decision = new Turn()
                .outcome(scored(60, keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.MISSING), List.of(), Set.of())).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(decision.resumeSection()).isEqualTo(Section.QUESTIONING);
        assertThat(decision.pendingStep()).isEqualTo(PendingStep.followUp(
                Section.QUESTIONING, CONCURRENCY, 3, question(2), FollowUpMode.PROBE_GAP, "kp2", null));
        assertThat(decision.memory().pendingStep()).isEqualTo(decision.pendingStep());
        assertThat(decision.interviewComplete()).isFalse();
    }

    @Test
    void aKeyPointTheEvaluatorDidNotJudgeCountsAsMissing() {
        TurnDecision decision = new Turn().outcome(scored(60, keyPoints("kp1", KeyPointStatus.COVERED), List.of(), Set.of())).decide();

        assertThat(decision.pendingStep().targetKeyPointId()).isEqualTo("kp2");
    }

    @Test
    void aNewClaimIsProbedOnceAndMarkedProbedInTheMemory() {
        TurnDecision decision = new Turn().outcome(scored(60, keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.COVERED),
                List.of("Used Kafka", "Led a team"), Set.of())).decide();

        assertThat(decision.pendingStep()).isEqualTo(PendingStep.followUp(
                Section.QUESTIONING, CONCURRENCY, 3, question(2), FollowUpMode.PROBE_CLAIM, null, "Used Kafka"));
        assertThat(decision.memory().candidateClaims()).extracting(Claim::text, Claim::probed)
                .containsExactly(tuple("Used Kafka", true), tuple("Led a team", false));
    }

    @Test
    void onlyOneClaimIsProbedPerSectionStarted() {
        // the questioning section has already probed one claim: no second one
        InterviewMemory probedAlready = memory(questioningProgress(1), Map.of(),
                List.of(new Claim("Old claim", true, answer(1))));

        TurnDecision decision = new Turn().memory(probedAlready).outcome(scored(60,
                keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.COVERED), List.of("Used Kafka"), Set.of())).decide();

        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.memory().candidateClaims()).extracting(Claim::probed).containsExactly(true, false);
    }

    @Test
    void aClaimMayBeProbedAgainOnceANewSectionHasStarted() {
        Map<Section, SectionProgress> progress = questioningProgress(6);
        InterviewMemory probedInQuestioning = memory(progress, Map.of(), List.of(new Claim("Old claim", true, answer(1))));

        TurnDecision decision = new Turn().state(InterviewState.CODING).plan(EngineFixtures.fullLoop()).memory(probedInQuestioning)
                .answered(coding(30, 3, T0.plus(Duration.ofMinutes(25)), "kp1"))
                .now(T0.plus(Duration.ofMinutes(27)))
                .outcome(scored(60, keyPoints("kp1", KeyPointStatus.COVERED), List.of("Wrote a compiler"), Set.of())).decide();

        assertThat(decision.pendingStep().followUpMode()).isEqualTo(FollowUpMode.PROBE_CLAIM);
        assertThat(decision.pendingStep().targetClaim()).isEqualTo("Wrote a compiler");
    }

    @Test
    void aClaimFromAnEarlierAnswerIsNotProbedAfterALaterAnswer() {
        InterviewMemory withIntroClaim = memory(questioningProgress(0), Map.of(), List.of(new Claim("Used Kafka", false, answer(1))));

        TurnDecision decision = new Turn().memory(withIntroClaim).decide();

        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.memory().candidateClaims()).extracting(Claim::probed).containsExactly(false);
    }

    @Test
    void anExcellentAnswerIsDeepenedOneLevelHarderOnTheSameTopic() {
        TurnDecision decision = new Turn().outcome(scored(92)).decide();

        assertThat(decision.pendingStep()).isEqualTo(PendingStep.followUp(
                Section.QUESTIONING, CONCURRENCY, 4, question(2), FollowUpMode.DEEPEN, null, null));
        assertThat(decision.nextState()).isEqualTo(InterviewState.FOLLOW_UP);
    }

    @Test
    void anExcellentAnswerAtTheTopOfTheBandIsNotDeepenedBecauseThereIsNoHarderLevel() {
        TurnDecision decision = new Turn().answered(technical(2, CONCURRENCY, 4, T0, "kp1", "kp2")).outcome(scored(92)).decide();

        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
    }

    @Test
    void anExcellentAnswerAtTheTopOfTheTopicsRangeIsNotDeepened() {
        TurnDecision decision = new Turn().ranges(Map.of(CONCURRENCY, new TopicRange(1, 3), INDEXING, new TopicRange(1, 5)))
                .outcome(scored(92)).decide();

        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
    }

    @Test
    void aDeepeningNeedsTheTopicsRangeToBeKnown() {
        assertThatThrownBy(() -> new Turn().ranges(Map.of(INDEXING, new TopicRange(1, 5))).outcome(scored(92)).decide())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(CONCURRENCY);
    }

    @Test
    void aWeakAnswerMovesOnToTheNextTopicAtALowerLevelForTheWeakTopic() {
        TurnDecision decision = new Turn().outcome(scored(20, keyPoints("kp1", KeyPointStatus.MISSING, "kp2", KeyPointStatus.MISSING),
                List.of(), Set.of())).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.pendingStep()).as("the other skill is now least answered; a new topic starts at the candidate's level, "
                + "which the weak answer lowered").isEqualTo(PendingStep.nextTopic(Section.QUESTIONING, INDEXING, 2));
        assertThat(decision.memory().topicStats().get(CONCURRENCY)).isEqualTo(stat(1, 20, 2, 20));
        assertThat(decision.memory().overall()).isEqualTo(stat(1, 20, 2, 20));
    }

    @Test
    void theNextQuestionOnAStrongTopicIsAskedAtItsRaisedLevel() {
        Map<String, TopicStat> stats = Map.of(INDEXING, stat(1, 50, 3, 50));
        TurnDecision decision = new Turn().memory(memory(questioningProgress(1), stats, List.of()))
                .answered(technical(3, CONCURRENCY, 3, T0, "kp1", "kp2"))
                .outcome(scored(78, keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.COVERED), List.of(), Set.of())).decide();

        // concurrency scored 78: level 4; indexing has been asked as often, concurrency now has one answer, indexing one:
        // equal skill counts, then asked counts, then the topic with the most quota left (equal), then the key
        assertThat(decision.memory().topicStats().get(CONCURRENCY).level()).isEqualTo(4);
        assertThat(decision.pendingStep().topic()).isEqualTo(CONCURRENCY);
        assertThat(decision.pendingStep().difficulty()).isEqualTo(4);
    }

    // ---- sections ---------------------------------------------------------------------------------------------------

    @Test
    void whenTheLastSlotIsUsedTheSectionCompletesIntoTheNextSection() {
        Map<String, TopicStat> stats = Map.of(CONCURRENCY, stat(2, 60, 3, 60), INDEXING, stat(3, 60, 4, 60));
        InterviewMemory memory = new InterviewMemory(stats, List.of(), List.of(), List.of(), Map.of(), questioningProgress(5), null,
                stat(5, 80, 4, 80), List.of());
        TurnDecision decision = new Turn().memory(memory).now(T0.plus(Duration.ofMinutes(20)))
                .answered(technical(8, CONCURRENCY, 3, T0.plus(Duration.ofMinutes(18)), "kp1", "kp2")).decide();

        assertThat(decision.transitions()).hasSize(1);
        assertThat(decision.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.CODING);
        assertThat(decision.endReason()).isNull();
        // topic-less section: the candidate's level (average 0.6 * 60 + 0.4 * 80 = 68 keeps level 4)
        assertThat(decision.memory().overall()).isEqualTo(stat(6, 68, 4, 60));
        assertThat(decision.pendingStep()).isEqualTo(PendingStep.nextTopic(Section.CODING, null, 4));
    }

    @Test
    void aSlotsRemainingSectionKeepsGoing() {
        Map<String, TopicStat> stats = Map.of(CONCURRENCY, stat(2, 60, 3, 60), INDEXING, stat(2, 60, 4, 60));
        TurnDecision decision = new Turn().memory(memory(questioningProgress(4), stats, List.of())).now(T0.plus(Duration.ofMinutes(20)))
                .decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
    }

    @Test
    void theLastSectionOfThePlanCompletesIntoFinalEvaluation() {
        Map<String, TopicStat> stats = Map.of(CONCURRENCY, stat(1, 60, 3, 60), INDEXING, stat(1, 60, 3, 60));
        TurnDecision decision = new Turn().plan(EngineFixtures.technicalOnly()).memory(memory(questioningProgress(2), stats, List.of()))
                .now(T0.plus(Duration.ofMinutes(15))).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(decision.endReason()).isEqualTo(EndReason.PLAN_COMPLETE);
        assertThat(decision.pendingStep()).isNull();
        assertThat(decision.memory().pendingStep()).isNull();
        assertThat(decision.interviewComplete()).isTrue();
    }

    @Test
    void theLastSectionEndsByPlanWhenItsOwnBudgetIsUsedAndByTimeWhenTheDeadlineCutsItShort() {
        InterviewMemory memory = memory(questioningProgress(1), Map.of(CONCURRENCY, stat(1, 60, 3, 60)), List.of());
        Map<Section, SectionProgress> lateStart = new LinkedHashMap<>();
        lateStart.put(Section.QUESTIONING, new SectionProgress(1, T0.plus(Duration.ofMinutes(40))));
        InterviewMemory late = memory(lateStart, Map.of(CONCURRENCY, stat(1, 60, 3, 60)), List.of());

        TurnDecision budgetUsed = new Turn().plan(EngineFixtures.technicalOnly()).memory(memory).now(T0.plus(Duration.ofMinutes(23))).decide();
        TurnDecision budgetUsedAtTheEnd = new Turn().plan(EngineFixtures.technicalOnly()).memory(memory).now(DEADLINE.minusSeconds(90)).decide();
        // Questioning started 40 minutes in: its own budget runs to 64, but the deadline is at 60.
        TurnDecision cutShort = new Turn().plan(EngineFixtures.technicalOnly()).memory(late).now(DEADLINE.minusSeconds(90))
                .answered(technical(5, CONCURRENCY, 3, T0.plus(Duration.ofMinutes(55)), "kp1", "kp2")).decide();

        assertThat(budgetUsed.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(budgetUsed.endReason()).isEqualTo(EndReason.PLAN_COMPLETE);
        assertThat(budgetUsed.rationale().get("next")).isEqualTo("end: every planned section has run");
        assertThat(budgetUsedAtTheEnd.endReason()).as("the plan's time ran out before the deadline mattered").isEqualTo(EndReason.PLAN_COMPLETE);
        assertThat(cutShort.event()).isEqualTo(InterviewEvent.TIME_UP);
        assertThat(cutShort.endReason()).isEqualTo(EndReason.TIME_UP);
        assertThat(cutShort.rationale().get("next")).isEqualTo("end: time is up, 1m30s left in the interview, a new QUESTIONING question needs 2m00s");
    }

    @Test
    void lessThanTheTimeOfOneQuestionLeftCompletesTheSectionEvenWithSlotsLeftButNeverBefore() {
        Instant twoMinutesLeft = T0.plus(Duration.ofMinutes(22));
        Map<String, TopicStat> stats = Map.of(CONCURRENCY, stat(1, 60, 3, 60));
        InterviewMemory memory = memory(questioningProgress(1), stats, List.of());

        TurnDecision exhausted = new Turn().memory(memory).now(twoMinutesLeft.plusNanos(1)).decide();
        TurnDecision justBefore = new Turn().memory(memory).now(twoMinutesLeft).decide();

        assertThat(exhausted.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(exhausted.nextState()).isEqualTo(InterviewState.CODING);
        assertThat(exhausted.rationale().get("section")).startsWith("QUESTIONING complete: 1m59s left, a new question needs 2m00s");
        assertThat(justBefore.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(justBefore.nextState()).isEqualTo(InterviewState.QUESTIONING);
    }

    @Test
    void theSectionBudgetIsCountedFromTheFirstQuestionOfTheSectionNotFromTheInterviewStart() {
        Instant late = T0.plus(Duration.ofMinutes(30));
        Map<Section, SectionProgress> progress = new LinkedHashMap<>();
        progress.put(Section.QUESTIONING, new SectionProgress(1, late));

        TurnDecision decision = new Turn().plan(EngineFixtures.technicalOnly()).memory(memory(progress, Map.of(), List.of()))
                .now(late.plus(Duration.ofMinutes(10))).answered(technical(5, CONCURRENCY, 3, late.plus(Duration.ofMinutes(5)), "kp1", "kp2"))
                .decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
    }

    @Test
    void aLateSectionDoesNotTakeTheTimePlannedForTheSectionsAfterIt() {
        // Questioning started 30 minutes in instead of 4: 10 minutes later its own budget has 14 minutes left, but coding and
        // design keep their 30 planned minutes, so only 60 - 40 - 30 < 0 is left for it.
        Instant late = T0.plus(Duration.ofMinutes(30));
        Map<Section, SectionProgress> progress = new LinkedHashMap<>();
        progress.put(Section.QUESTIONING, new SectionProgress(1, late));

        TurnDecision decision = new Turn().memory(memory(progress, Map.of(), List.of())).now(late.plus(Duration.ofMinutes(10)))
                .answered(technical(5, CONCURRENCY, 3, late.plus(Duration.ofMinutes(5)), "kp1", "kp2")).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.rationale().get("section")).isEqualTo(
                "QUESTIONING complete: 0m00s left, a new question needs 2m00s (2 of 6 asked)");
        // Coding would get 60 - 40 - 14 = 6 minutes, too few for a problem: it is skipped, and the design keeps its 14.
        assertThat(decision.nextState()).isEqualTo(InterviewState.SYSTEM_DESIGN);
        assertThat(decision.rationale().get("skipped")).isEqualTo("CODING skipped: 6m00s left for it, a question needs 8m00s");
        assertThat(decision.pendingStep().section()).isEqualTo(Section.SYSTEM_DESIGN);
    }

    @Test
    void theFirstQuestionOfASectionStartsItsBudgetEvenWhenTheAnswerIsLate() {
        // the question was asked at T0, answered 30 minutes later: that single answer exhausts the 24 minute budget
        TurnDecision decision = new Turn().now(T0.plus(Duration.ofMinutes(30))).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.CODING);
    }

    @Test
    void aCodingSectionWithTwoProblemsRepeatsBeforeCompleting() {
        Map<Section, SectionProgress> progress = new LinkedHashMap<>();
        progress.put(Section.CODING, new SectionProgress(0, T0.plus(Duration.ofMinutes(28))));
        Instant now = T0.plus(Duration.ofMinutes(35));
        Turn first = new Turn().state(InterviewState.CODING).memory(memory(progress, Map.of(), List.of()))
                .answered(coding(10, 3, T0.plus(Duration.ofMinutes(28)), "kp1")).now(now)
                .outcome(scored(70, keyPoints("kp1", KeyPointStatus.COVERED), List.of(), Set.of()));

        TurnDecision afterFirst = first.decide();

        assertThat(afterFirst.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(afterFirst.nextState()).isEqualTo(InterviewState.CODING);
        assertThat(afterFirst.pendingStep()).isEqualTo(PendingStep.nextTopic(Section.CODING, null, 3));

        Turn second = new Turn().state(InterviewState.CODING).memory(afterFirst.memory().withPendingStep(null))
                .answered(coding(11, 3, now, "kp1")).now(now.plus(Duration.ofMinutes(5)))
                .outcome(scored(70, keyPoints("kp1", KeyPointStatus.COVERED), List.of(), Set.of()));
        TurnDecision afterSecond = second.decide();

        assertThat(afterSecond.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(afterSecond.nextState()).isEqualTo(InterviewState.SYSTEM_DESIGN);
        assertThat(afterSecond.pendingStep()).isEqualTo(PendingStep.nextTopic(Section.SYSTEM_DESIGN, null, 3));
    }

    @Test
    void aDesignAnswerWithoutFollowUpEndsThePlan() {
        Turn turn = new Turn().state(InterviewState.SYSTEM_DESIGN).answered(design(20, 3, T0.plus(Duration.ofMinutes(45)), "kp1"))
                .now(T0.plus(Duration.ofMinutes(52)))
                .outcome(scored(70, keyPoints("kp1", KeyPointStatus.COVERED), List.of(), Set.of()));

        TurnDecision decision = turn.decide();

        assertThat(decision.transitions()).hasSize(1);
        assertThat(decision.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(decision.endReason()).isEqualTo(EndReason.PLAN_COMPLETE);
        assertThat(decision.interviewComplete()).isTrue();
    }

    /** A scored design: every dimension of the design rubric at {@code rating}, except those given. */
    private static EvaluationOutcome designOutcome(double score, int rating, Object... criteriaAndRatings) {
        Map<Criterion, Integer> ratings = new EnumMap<>(Criterion.class);
        for (int i = 0; i < criteriaAndRatings.length; i += 2) {
            ratings.put((Criterion) criteriaAndRatings[i], (Integer) criteriaAndRatings[i + 1]);
        }
        List<CriterionScore> criteria = RubricRegistry.forEvaluator(Evaluator.SYSTEM_DESIGN).weights().stream()
                .map(weight -> new CriterionScore(weight.criterion(), ratings.getOrDefault(weight.criterion(), rating),
                        weight.weight().doubleValue(), "because"))
                .toList();
        return EvaluationOutcome.scored(score, "A design.", List.of(), List.of(), Set.of(), List.of(), criteria);
    }

    @Test
    void aDesignIsProbedOnTheDimensionWhereItLosesTheMostWeightedPoints() {
        // CACHING 1 loses 0.07 x 3 = 0.21, SCALABILITY 2 loses 0.12 x 2 = 0.24: scalability is asked about first.
        Turn turn = new Turn().state(InterviewState.SYSTEM_DESIGN).answered(design(20, 3, T0.plus(Duration.ofMinutes(45)), "kp1", "kp2"))
                .now(T0.plus(Duration.ofMinutes(48)))
                .outcome(designOutcome(60, 3, Criterion.CACHING, 1, Criterion.SCALABILITY, 2));

        TurnDecision decision = turn.decide();

        assertThat(decision.nextState()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(decision.resumeSection()).isEqualTo(Section.SYSTEM_DESIGN);
        assertThat(decision.pendingStep()).isEqualTo(PendingStep.designProbe(3, question(20), Criterion.SCALABILITY));
        assertThat(decision.rationale().get("followUp"))
                .startsWith("PROBE_DIMENSION SCALABILITY: rated 2 of 4 with weight 0.12, shortfall 0.24; of the dimensions not probed yet");
        assertThat(decision.rationale().get("next")).isEqualTo("follow-up PROBE_DIMENSION on the design's SCALABILITY at level 3 (MEDIUM)");
    }

    @Test
    void aDimensionTheThreadProbedAlreadyIsNotProbedAgain() {
        Turn turn = inFollowUp(Section.SYSTEM_DESIGN, followUp(21, Section.SYSTEM_DESIGN, null, 3, 1, T0.plus(Duration.ofMinutes(45)), "kp1"))
                .now(T0.plus(Duration.ofMinutes(50)))
                .probed(Criterion.SCALABILITY)
                .outcome(designOutcome(62, 3, Criterion.CACHING, 1, Criterion.SCALABILITY, 2));

        TurnDecision decision = turn.decide();

        assertThat(decision.nextState()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(decision.pendingStep()).isEqualTo(PendingStep.designProbe(3, question(21), Criterion.CACHING));
        assertThat(decision.rationale().get("followUp")).endsWith("(probed already: [SCALABILITY])");
    }

    @Test
    void aWeakDesignIsStillProbedBecauseTheDiscussionIsItsChanceToShowMore() {
        Turn turn = new Turn().state(InterviewState.SYSTEM_DESIGN).answered(design(20, 3, T0.plus(Duration.ofMinutes(45))))
                .now(T0.plus(Duration.ofMinutes(48)))
                .outcome(designOutcome(15, 0, Criterion.HIGH_LEVEL_DESIGN, 1));

        assertThat(turn.decide().pendingStep()).as("SCALABILITY 0 loses 0.48; architecture, at 1, loses 0.36")
                .isEqualTo(PendingStep.designProbe(3, question(20), Criterion.SCALABILITY));
    }

    @Test
    void theDiscussionEndsAtThePlansNumberOfProbesOrWhenNothingFallsShort() {
        Turn atLimit = inFollowUp(Section.SYSTEM_DESIGN, followUp(24, Section.SYSTEM_DESIGN, null, 3, 4, T0.plus(Duration.ofMinutes(45))))
                .now(T0.plus(Duration.ofMinutes(50))).outcome(designOutcome(50, 2));
        TurnDecision limited = atLimit.decide();
        assertThat(limited.pendingStep().kind()).isEqualTo(PendingStep.Kind.NEXT_TOPIC);
        assertThat(limited.rationale().get("followUp")).isEqualTo("MOVE_ON: the thread has reached the follow-up depth");

        Turn perfect = new Turn().state(InterviewState.SYSTEM_DESIGN).answered(design(20, 3, T0.plus(Duration.ofMinutes(45))))
                .now(T0.plus(Duration.ofMinutes(48))).outcome(designOutcome(100, 4));
        assertThat(perfect.decide().rationale().get("followUp")).isEqualTo("MOVE_ON: no rule calls for a follow-up");

        Turn disabled = new Turn().state(InterviewState.SYSTEM_DESIGN).answered(design(20, 3, T0.plus(Duration.ofMinutes(45))))
                .plan(EngineFixtures.withDesignProbes(EngineFixtures.fullLoop(), 0))
                .now(T0.plus(Duration.ofMinutes(48))).outcome(designOutcome(40, 2));
        assertThat(disabled.decide().rationale().get("followUp")).as("a plan without design probes")
                .isEqualTo("MOVE_ON: the thread has reached the follow-up depth");
    }

    @Test
    void aDesignAnswerWithoutAScoreOrRepeatingAnEarlierOneIsNotProbed() {
        Turn pending = new Turn().state(InterviewState.SYSTEM_DESIGN).answered(design(20, 3, T0.plus(Duration.ofMinutes(45))))
                .now(T0.plus(Duration.ofMinutes(48))).outcome(EvaluationOutcome.pending());

        assertThat(pending.decide().rationale().get("followUp")).isEqualTo("MOVE_ON: the answer has no score (evaluation pending or skipped)");
    }

    // ---- follow-up thread --------------------------------------------------------------------------------------------

    private static Turn inFollowUp(Section resume, AnsweredQuestion answered) {
        return new Turn().state(InterviewState.FOLLOW_UP).resume(resume).answered(answered);
    }

    @Test
    void aFollowUpThatStillShowsAGapIsFollowedUpAgainWhileDepthAllows() {
        Turn turn = inFollowUp(Section.QUESTIONING, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0, "kp1", "kp2"))
                .memory(memory(questioningProgress(1), Map.of(), List.of()))
                .outcome(scored(55, keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.MISSING), List.of(), Set.of()));

        TurnDecision decision = turn.decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(decision.resumeSection()).isEqualTo(Section.QUESTIONING);
        assertThat(decision.pendingStep()).isEqualTo(PendingStep.followUp(
                Section.QUESTIONING, CONCURRENCY, 3, question(3), FollowUpMode.PROBE_GAP, "kp2", null));
    }

    @Test
    void theFollowUpThreadEndsAtTheMaximumDepthAndTheSectionResumes() {
        Turn turn = inFollowUp(Section.QUESTIONING, followUp(4, Section.QUESTIONING, CONCURRENCY, 3, 2, T0, "kp1", "kp2"))
                .memory(memory(questioningProgress(1), Map.of(), List.of()))
                .outcome(scored(55, keyPoints("kp1", KeyPointStatus.COVERED, "kp2", KeyPointStatus.MISSING), List.of(), Set.of()));

        TurnDecision decision = turn.decide();

        assertThat(decision.transitions()).hasSize(1);
        assertThat(decision.event()).isEqualTo(InterviewEvent.FOLLOW_UP_DONE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.resumeSection()).isNull();
        assertThat(decision.pendingStep().kind()).isEqualTo(PendingStep.Kind.NEXT_TOPIC);
        assertThat(decision.pendingStep().section()).isEqualTo(Section.QUESTIONING);
    }

    @Test
    void aFollowUpThatClosesTheSectionTakesTwoTransitionsInOneTurn() {
        Map<String, TopicStat> stats = Map.of(CONCURRENCY, stat(3, 60, 3, 60), INDEXING, stat(3, 60, 3, 60));
        Turn turn = inFollowUp(Section.QUESTIONING, followUp(30, Section.QUESTIONING, CONCURRENCY, 3, 1, T0.plus(Duration.ofMinutes(18))))
                .memory(memory(questioningProgress(6), stats, List.of())).now(T0.plus(Duration.ofMinutes(20)))
                .outcome(scored(60));

        TurnDecision decision = turn.decide();

        assertThat(decision.transitions()).extracting(StateTransition::event)
                .containsExactly(InterviewEvent.FOLLOW_UP_DONE, InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.transitions()).extracting(StateTransition::to).containsExactly(InterviewState.QUESTIONING, InterviewState.CODING);
        assertThat(decision.transitions().getFirst().from()).isEqualTo(InterviewState.FOLLOW_UP);
        assertThat(decision.event()).isEqualTo(InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.CODING);
        assertThat(decision.pendingStep().section()).isEqualTo(Section.CODING);
    }

    @Test
    void aFollowUpThatClosesTheLastSectionEndsThePlan() {
        Turn turn = inFollowUp(Section.SYSTEM_DESIGN, followUp(40, Section.SYSTEM_DESIGN, null, 3, 2, T0.plus(Duration.ofMinutes(48))))
                .memory(memory(new LinkedHashMap<>(Map.of(Section.SYSTEM_DESIGN, new SectionProgress(1, T0.plus(Duration.ofMinutes(45))))),
                        Map.of(), List.of()))
                .now(T0.plus(Duration.ofMinutes(52))).outcome(scored(70));

        TurnDecision decision = turn.decide();

        assertThat(decision.transitions()).extracting(StateTransition::event)
                .containsExactly(InterviewEvent.FOLLOW_UP_DONE, InterviewEvent.SECTION_COMPLETE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(decision.endReason()).isEqualTo(EndReason.PLAN_COMPLETE);
        assertThat(decision.pendingStep()).isNull();
    }

    @Test
    void aCodingFollowUpResumesCodingWhileSlotsRemain() {
        Map<Section, SectionProgress> progress = new LinkedHashMap<>();
        progress.put(Section.CODING, new SectionProgress(1, T0.plus(Duration.ofMinutes(28))));
        Turn turn = inFollowUp(Section.CODING, followUp(12, Section.CODING, null, 3, 2, T0.plus(Duration.ofMinutes(33))))
                .memory(memory(progress, Map.of(), List.of())).now(T0.plus(Duration.ofMinutes(35))).outcome(scored(70));

        TurnDecision decision = turn.decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.FOLLOW_UP_DONE);
        assertThat(decision.nextState()).isEqualTo(InterviewState.CODING);
        assertThat(decision.pendingStep()).isEqualTo(PendingStep.nextTopic(Section.CODING, null, 3));
    }

    @Test
    void aFollowUpWithNoSectionTimeLeftIsNotFollowedUp() {
        Turn turn = inFollowUp(Section.QUESTIONING, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0, "kp1", "kp2"))
                .memory(memory(questioningProgress(1), Map.of(), List.of())).now(T0.plus(Duration.ofMinutes(22)))
                .outcome(scored(55, keyPoints("kp1", KeyPointStatus.MISSING), List.of(), Set.of()));

        assertThat(turn.decide().event()).as("2 minutes of the 24 left").isEqualTo(InterviewEvent.FOLLOW_UP_DONE);
    }

    @Test
    void aFollowUpDeepeningUsesTheDifficultyOfTheAnsweredFollowUp() {
        Turn turn = inFollowUp(Section.QUESTIONING, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0))
                .memory(memory(questioningProgress(1), Map.of(), List.of())).outcome(scored(95));

        TurnDecision decision = turn.decide();

        assertThat(decision.pendingStep().followUpMode()).isEqualTo(FollowUpMode.DEEPEN);
        assertThat(decision.pendingStep().difficulty()).isEqualTo(4);
        assertThat(decision.pendingStep().parentQuestionId()).isEqualTo(question(3));
    }

    @Test
    void aGapFollowUpKeepsTheDifficultyOfTheQuestionItProbes() {
        TurnDecision decision = new Turn().answered(technical(2, CONCURRENCY, 2, T0, "kp1", "kp2"))
                .outcome(scored(60, keyPoints("kp1", KeyPointStatus.MISSING), List.of(), Set.of())).decide();

        assertThat(decision.pendingStep().difficulty()).isEqualTo(2);
    }

    // ---- degraded evaluation ------------------------------------------------------------------------------------------

    @Test
    void anAnswerWhoseEvaluationIsPendingMovesOnWithoutLearning() {
        TurnDecision decision = new Turn().outcome(EvaluationOutcome.pending()).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.pendingStep().kind()).isEqualTo(PendingStep.Kind.NEXT_TOPIC);
        assertThat(decision.pendingStep().difficulty()).as("the same difficulty").isEqualTo(3);
        assertThat(decision.memory().answerSummaries()).isEmpty();
    }

    @Test
    void aPendingFollowUpEndsTheThread() {
        TurnDecision decision = inFollowUp(Section.QUESTIONING, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0))
                .memory(memory(questioningProgress(1), Map.of(), List.of())).outcome(EvaluationOutcome.pending()).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.FOLLOW_UP_DONE);
    }

    @Test
    void aSkippedRootQuestionCountsZeroAndMovesOn() {
        TurnDecision decision = new Turn().outcome(EvaluationOutcome.skipped()).decide();

        assertThat(decision.nextState()).isEqualTo(InterviewState.QUESTIONING);
        assertThat(decision.memory().topicStats().get(CONCURRENCY).lastScore()).isEqualTo(0.0);
        assertThat(decision.memory().sectionProgress().get(Section.QUESTIONING).slotsUsed()).isEqualTo(1);
    }

    @Test
    void aSkippedFollowUpEndsTheThreadAndAddsNothing() {
        InterviewMemory memory = memory(questioningProgress(1), Map.of(CONCURRENCY, stat(1, 60, 3, 60)), List.of());

        TurnDecision decision = inFollowUp(Section.QUESTIONING, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0))
                .memory(memory).outcome(EvaluationOutcome.skipped()).decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.FOLLOW_UP_DONE);
        assertThat(decision.memory().topicStats()).isEqualTo(memory.topicStats());
        assertThat(decision.memory().sectionProgress().get(Section.QUESTIONING).slotsUsed()).isEqualTo(1);
    }

    // ---- ending: time, candidate ------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INTRODUCTION", "QUESTIONING", "FOLLOW_UP", "CODING", "SYSTEM_DESIGN"})
    void theCandidateEndingTheInterviewWithAnAnswerStillCountsTheAnswer(InterviewState state) {
        Turn turn = turnIn(state).endRequested();

        TurnDecision decision = turn.decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.END_REQUESTED);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(decision.endReason()).isEqualTo(EndReason.CANDIDATE_ENDED);
        assertThat(decision.pendingStep()).isNull();
        assertThat(decision.interviewComplete()).isTrue();
        assertThat(decision.memory().answerSummaries()).hasSize(1);
        assertThat(decision.resumeSection()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INTRODUCTION", "QUESTIONING", "FOLLOW_UP", "CODING", "SYSTEM_DESIGN"})
    void anAnswerAfterTheDeadlineEndsTheInterviewByTimeUp(InterviewState state) {
        Turn turn = turnIn(state).now(DEADLINE.plusNanos(1));

        TurnDecision decision = turn.decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.TIME_UP);
        assertThat(decision.nextState()).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(decision.endReason()).isEqualTo(EndReason.TIME_UP);
        assertThat(decision.pendingStep()).isNull();
        assertThat(decision.memory().answerSummaries()).hasSize(1);
    }

    @Test
    void anAnswerExactlyAtTheDeadlineIsNotLateButNoQuestionFitsAnyMore() {
        TurnDecision atTheDeadline = new Turn().now(DEADLINE).decide();
        TurnDecision late = new Turn().now(DEADLINE.plusNanos(1)).decide();

        assertThat(atTheDeadline.event()).isEqualTo(InterviewEvent.TIME_UP);
        assertThat(atTheDeadline.rationale().get("next")).startsWith("end: time is up, no planned section can still start");
        assertThat(late.rationale().get("next")).isEqualTo("end: the deadline has passed");
    }

    @Test
    void endingByTheCandidateTakesPrecedenceOverTheDeadline() {
        TurnDecision decision = new Turn().now(DEADLINE.plusSeconds(10)).endRequested().decide();

        assertThat(decision.event()).isEqualTo(InterviewEvent.END_REQUESTED);
        assertThat(decision.endReason()).isEqualTo(EndReason.CANDIDATE_ENDED);
    }

    private static Turn turnIn(InterviewState state) {
        return switch (state) {
            case INTRODUCTION -> new Turn().state(state).answered(intro(T0));
            case FOLLOW_UP -> inFollowUp(Section.QUESTIONING, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0))
                    .memory(memory(questioningProgress(1), Map.of(), List.of()));
            case CODING -> new Turn().state(state).answered(coding(10, 3, T0));
            case SYSTEM_DESIGN -> new Turn().state(state).answered(design(10, 3, T0));
            default -> new Turn().state(state);
        };
    }

    // ---- invalid use ------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = InterviewState.class, names = {"INIT", "FINAL_EVALUATION", "COMPLETED", "ABANDONED"})
    void aStateThatDoesNotAcceptAnswersIsRejected(InterviewState state) {
        assertThatThrownBy(() -> new Turn().state(state).decide())
                .isInstanceOfSatisfying(IllegalTransitionException.class, e -> {
                    assertThat(e.from()).isEqualTo(state);
                    assertThat(e.event()).isEqualTo(InterviewEvent.ANSWER_EVALUATED);
                });
    }

    @Test
    void factsThatContradictTheStateAreRejected() {
        assertThatThrownBy(() -> new Turn().state(InterviewState.INTRODUCTION).decide())
                .as("a technical question in the introduction").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Turn().answered(intro(T0)).decide())
                .as("the introduction answered in QUESTIONING").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Turn().state(InterviewState.CODING).decide())
                .as("a technical question in CODING").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Turn().state(InterviewState.FOLLOW_UP).resume(Section.QUESTIONING).decide())
                .as("a root question in FOLLOW_UP").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inFollowUp(null, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0)).decide())
                .as("FOLLOW_UP without a resume section").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inFollowUp(Section.CODING, followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0)).decide())
                .as("a follow-up of another section than the thread's").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Turn().answered(followUp(3, Section.QUESTIONING, CONCURRENCY, 3, 1, T0)).decide())
                .as("a follow-up answered in QUESTIONING").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Turn().resume(Section.QUESTIONING).decide())
                .as("a resume section outside FOLLOW_UP").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Turn().state(InterviewState.CODING).plan(EngineFixtures.technicalOnly())
                .answered(coding(10, 3, T0)).decide())
                .as("a plan without the answered section").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNextTopicNeedsItsDifficultyRange() {
        assertThatThrownBy(() -> new Turn().ranges(Map.of(CONCURRENCY, new TopicRange(1, 5))).outcome(EvaluationOutcome.skipped()).decide())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDecisionIsADeterministicFunctionOfItsInput() {
        Turn turn = new Turn().outcome(scored(60, keyPoints("kp1", KeyPointStatus.MISSING), List.of("Used Kafka"), Set.of(EvaluationFlag.OFF_TOPIC)));

        assertThat(turn.decide()).isEqualTo(turn.decide());
    }

    @Test
    void theInputMemoryIsNeverModified() {
        InterviewMemory before = memory(questioningProgress(1), Map.of(CONCURRENCY, stat(1, 60, 3, 60)), List.of());
        Turn turn = new Turn().memory(before).outcome(scored(60, keyPoints("kp1", KeyPointStatus.MISSING), List.of("c"), Set.of()));

        turn.decide();

        assertThat(turn.memory).isEqualTo(memory(questioningProgress(1), Map.of(CONCURRENCY, stat(1, 60, 3, 60)), List.of()));
    }

    @Test
    void turnInputRejectsMissingParts() {
        Turn ok = new Turn();
        assertThatThrownBy(() -> new TurnInput(null, null, ok.plan, ok.memory, ok.ranges, ok.answered, ok.outcome, NOW, DEADLINE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnInput(ok.state, null, null, ok.memory, ok.ranges, ok.answered, ok.outcome, NOW, DEADLINE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnInput(ok.state, null, ok.plan, null, ok.ranges, ok.answered, ok.outcome, NOW, DEADLINE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnInput(ok.state, null, ok.plan, ok.memory, null, ok.answered, ok.outcome, NOW, DEADLINE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnInput(ok.state, null, ok.plan, ok.memory, ok.ranges, null, ok.outcome, NOW, DEADLINE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnInput(ok.state, null, ok.plan, ok.memory, ok.ranges, ok.answered, null, NOW, DEADLINE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnInput(ok.state, null, ok.plan, ok.memory, ok.ranges, ok.answered, ok.outcome, null, DEADLINE, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnInput(ok.state, null, ok.plan, ok.memory, ok.ranges, ok.answered, ok.outcome, NOW, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- résumé anchors (AI_ARCHITECTURE.md section 2.13) ----------------------------------------------------------------

    private static InterviewPlan personalised(int questions) {
        InterviewPlan plan = EngineFixtures.fullLoop();
        return new InterviewPlan(plan.version(), plan.sections(), plan.difficulty(), plan.maxFollowUpDepth(), plan.maxDesignProbes(),
                new com.techinterviewer.interview.domain.ResumePlan(questions, Map.of(CONCURRENCY, List.of("x2", "x1")),
                        Map.of("java", List.of("x1", "x3"), "postgresql", List.of("x4"))));
    }

    @Test
    void aQuestioningQuestionIsAnchoredOnTheStrongestUnusedExperienceOfItsTopicWithoutChangingTopicOrLevel() {
        TurnDecision plain = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).decide();
        TurnDecision anchored = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).plan(personalised(2)).decide();

        PendingStep step = anchored.pendingStep();
        assertThat(step.topic()).isEqualTo(plain.pendingStep().topic()).isEqualTo(CONCURRENCY);
        assertThat(step.difficulty()).isEqualTo(plain.pendingStep().difficulty());
        assertThat(step.resumeItemId()).isEqualTo("x2");
        assertThat(plain.pendingStep().resumeItemId()).isNull();
        assertThat(anchored.memory().resumeItemsAsked()).containsExactly("x2");
        assertThat(anchored.memory().pendingStep()).isEqualTo(step);
        assertThat(anchored.rationale().get("next")).endsWith("; anchored on résumé experience x2 (linked to the topic, résumé question 1 of 2)");
    }

    @Test
    void anExperienceIsNeverAnchoredTwiceAndTheSkillsExperiencesAreTheFallback() {
        InterviewMemory askedX2 = InterviewMemory.empty().withResumeItemAsked("x2");
        InterviewMemory askedBoth = askedX2.withResumeItemAsked("x1");

        TurnDecision second = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).plan(personalised(3)).memory(askedX2).decide();
        TurnDecision third = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).plan(personalised(3)).memory(askedBoth).decide();

        assertThat(second.pendingStep().resumeItemId()).isEqualTo("x1");
        assertThat(third.pendingStep().resumeItemId()).isEqualTo("x3");
        assertThat(third.rationale().get("next")).contains("linked to the skill, résumé question 3 of 3");
        assertThat(third.memory().resumeItemsAsked()).containsExactly("x2", "x1", "x3");
    }

    @Test
    void onceThePlannedResumeQuestionsAreAskedTheRestAreOrdinary() {
        InterviewMemory askedTwo = InterviewMemory.empty().withResumeItemAsked("x2").withResumeItemAsked("x1");

        TurnDecision decision = new Turn().state(InterviewState.INTRODUCTION).answered(intro(T0)).plan(personalised(2)).memory(askedTwo).decide();

        assertThat(decision.pendingStep().resumeItemId()).isNull();
        assertThat(decision.rationale().get("next")).doesNotContain("résumé");
        assertThat(decision.memory().resumeItemsAsked()).containsExactly("x2", "x1");
    }

    @Test
    void theAnchorsSurviveTheMemoryUpdateOfLaterAnswers() {
        InterviewMemory anchored = InterviewMemory.empty().withResumeItemAsked("x2");

        TurnDecision decision = new Turn().memory(anchored).decide();

        assertThat(decision.memory().resumeItemsAsked()).startsWith("x2");
    }
}
