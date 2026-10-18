package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.engine.EngineFixtures.answer;
import static com.techinterviewer.interview.engine.EngineFixtures.question;
import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.evaluation.RubricRegistry;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.evaluation.domain.Evaluator;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.ExperienceLevel;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.InterviewType;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.TargetRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Plays whole interviews through the planner and the turn decider with random (seeded) answers, evaluations that are
 * sometimes pending or skipped, and checks the invariants no single-turn test can: the interview always ends in
 * FINAL_EVALUATION by PLAN_COMPLETE, every section asks exactly its slots, every topic exactly its quota, every
 * question lies in its band and range, follow-ups never exceed the depth, and the state always matches the pending step.
 */
class TurnDeciderSimulationTest {

    private static final List<PlannerSkill> SKILLS = List.of(
            new PlannerSkill("java", List.of(new PlannerTopic("concurrency", 2, 5, 3), new PlannerTopic("collections", 1, 4, 1))),
            new PlannerSkill("postgresql", List.of(new PlannerTopic("indexing", 2, 5, 2))),
            new PlannerSkill("react", List.of(new PlannerTopic("hooks", 1, 3, 1), new PlannerTopic("state", 3, 5, 1))));

    private final Set<FollowUpMode> followUpModesSeen = EnumSet.noneOf(FollowUpMode.class);
    private int twoTransitionTurns;

    @Test
    void everyPlanShapePlaysThroughToItsEndWithEveryInvariantIntact() {
        int interviews = 0;
        for (int duration : new int[] {15, 30, 45, 60, 90}) {
            for (InterviewType type : InterviewType.values()) {
                for (ExperienceLevel experience : List.of(ExperienceLevel.INTERN, ExperienceLevel.MID, ExperienceLevel.STAFF)) {
                    for (InterviewDifficulty difficulty : InterviewDifficulty.values()) {
                        for (long seed = 1; seed <= 4; seed++) {
                            PlannerInput input = new PlannerInput(TargetRole.FULL_STACK, experience, difficulty, duration, type, SKILLS, 2);
                            play(input, seed);
                            interviews++;
                        }
                    }
                }
            }
        }
        assertThat(interviews).isEqualTo(5 * 4 * 3 * 3 * 4);
        assertThat(followUpModesSeen).as("the random answers exercise every kind of follow-up").containsExactlyInAnyOrder(FollowUpMode.values());
        assertThat(twoTransitionTurns).as("follow-ups that close their section happen").isPositive();
    }

    private void play(PlannerInput input, long seed) {
        String context = input.type() + " " + input.durationMinutes() + " min " + input.experience() + " " + input.difficulty() + " seed " + seed;
        InterviewPlan plan = InterviewPlanner.plan(input);
        DifficultyBand band = plan.difficulty();
        Map<String, TopicRange> ranges = input.topicRanges();
        Instant deadline = DeadlinePolicy.deadline(EngineFixtures.T0, input.durationMinutes());
        Random random = new Random(seed);

        InterviewState state = InterviewState.INTRODUCTION;
        Section resume = null;
        InterviewMemory memory = InterviewMemory.empty();
        AnsweredQuestion current = new AnsweredQuestion(answer(1), question(1), QuestionKind.INTRO, Section.INTRODUCTION, null, band.start(), 0,
                EngineFixtures.T0, List.of());
        Map<Section, Integer> rootsAsked = new EnumMap<>(Section.class);
        rootsAsked.put(Section.INTRODUCTION, 1);
        Map<String, Integer> topicRoots = new HashMap<>();
        int sequence = 1;
        Instant now = EngineFixtures.T0;
        TurnDecision last = null;
        Set<Criterion> probedInThread = EnumSet.noneOf(Criterion.class);

        for (int turn = 0; turn < 400; turn++) {
            now = now.plusSeconds(30);
            TurnDecision decision = TurnDecider.decide(new TurnInput(
                    state, resume, plan, memory, ranges, current, randomOutcome(random, current), now, deadline, false, false, probedInThread));
            assertThat(decision.transitions().getFirst().from()).as(context).isEqualTo(state);
            memory = decision.memory();
            state = decision.nextState();
            resume = decision.resumeSection();
            last = decision;
            if (decision.interviewComplete()) {
                break;
            }
            if (decision.transitions().size() == 2) {
                twoTransitionTurns++;
            }
            PendingStep step = decision.pendingStep();
            if (step.followUpMode() != null) {
                followUpModesSeen.add(step.followUpMode());
            }
            assertThat(memory.pendingStep()).as(context).isEqualTo(step);
            assertThat(step.difficulty()).as(context).isBetween(band.min(), band.max());
            if (step.topic() != null) {
                assertThat(step.difficulty()).as(context).isBetween(ranges.get(step.topic()).min(), ranges.get(step.topic()).max());
            }
            int depth;
            QuestionKind kind;
            if (step.kind() == PendingStep.Kind.FOLLOW_UP) {
                assertThat(state).as(context).isEqualTo(InterviewState.FOLLOW_UP);
                assertThat(resume).as(context).isEqualTo(step.section());
                assertThat(step.parentQuestionId()).as(context).isEqualTo(current.questionId());
                depth = current.followUpDepth() + 1;
                assertThat(depth).as("follow-up depth " + context).isLessThanOrEqualTo(plan.maxDepthIn(step.section()));
                assertThat(step.followUpMode() == FollowUpMode.PROBE_DIMENSION).as("design threads, and only they, probe dimensions " + context)
                        .isEqualTo(step.section() == Section.SYSTEM_DESIGN);
                if (step.targetCriterion() != null) {
                    assertThat(probedInThread).as("a dimension is probed once per thread " + context).doesNotContain(step.targetCriterion());
                    probedInThread.add(step.targetCriterion());
                }
                kind = QuestionKind.FOLLOW_UP;
            } else {
                assertThat(state).as(context).isEqualTo(step.section().state());
                assertThat(resume).as(context).isNull();
                depth = 0;
                probedInThread = EnumSet.noneOf(Criterion.class);
                kind = switch (step.section()) {
                    case QUESTIONING -> QuestionKind.TECHNICAL;
                    case CODING -> QuestionKind.CODING;
                    case SYSTEM_DESIGN -> QuestionKind.SYSTEM_DESIGN;
                    case INTRODUCTION -> throw new AssertionError("nothing leads back into the introduction");
                };
                rootsAsked.merge(step.section(), 1, Integer::sum);
                if (step.section() == Section.QUESTIONING) {
                    assertThat(plan.section(Section.QUESTIONING).orElseThrow().topicQuotas()).as(context).containsKey(step.topic());
                    topicRoots.merge(step.topic(), 1, Integer::sum);
                }
            }
            sequence++;
            current = new AnsweredQuestion(answer(sequence), question(sequence), kind, step.section(), step.topic(), step.difficulty(), depth,
                    now, List.of("kp1", "kp2", "kp3"));
        }

        assertThat(last).as(context).isNotNull();
        assertThat(last.interviewComplete()).as("the interview ends: " + context).isTrue();
        assertThat(state).as(context).isEqualTo(InterviewState.FINAL_EVALUATION);
        assertThat(last.endReason()).as(context).isEqualTo(EndReason.PLAN_COMPLETE);
        assertThat(memory.pendingStep()).as(context).isNull();
        for (SectionPlan section : plan.sections()) {
            assertThat(rootsAsked.get(section.section())).as(section.section() + " asks exactly its slots: " + context).isEqualTo(section.slots());
            assertThat(memory.sectionProgress().get(section.section()).slotsUsed()).as(context).isEqualTo(section.slots());
        }
        assertThat(rootsAsked.keySet()).as(context).containsExactlyInAnyOrderElementsOf(
                plan.sections().stream().map(SectionPlan::section).toList());
        assertThat(topicRoots).as("every topic is asked exactly its quota: " + context)
                .isEqualTo(plan.section(Section.QUESTIONING).map(SectionPlan::topicQuotas).orElse(Map.of()));
    }

    private static EvaluationOutcome randomOutcome(Random random, AnsweredQuestion answered) {
        int roll = random.nextInt(100);
        if (roll < 6) {
            return EvaluationOutcome.pending();
        }
        if (roll < 14) {
            return EvaluationOutcome.skipped();
        }
        List<KeyPointAssessment> keyPoints = new ArrayList<>();
        for (String id : answered.expectedKeyPointIds()) {
            keyPoints.add(EngineFixtures.keyPoint(id, KeyPointStatus.values()[random.nextInt(3)]));
        }
        List<String> claims = random.nextInt(4) == 0 ? List.of("claim " + answered.questionId()) : List.of();
        Set<EvaluationFlag> flags = random.nextInt(10) == 0 ? Set.of(EvaluationFlag.OFF_TOPIC) : Set.of();
        double score = Math.round(random.nextDouble() * 10_000) / 100.0;
        List<CriterionScore> criteria = answered.section() != Section.SYSTEM_DESIGN ? List.of()
                : RubricRegistry.forEvaluator(Evaluator.SYSTEM_DESIGN).weights().stream()
                        .map(weight -> new CriterionScore(weight.criterion(), random.nextInt(5), weight.weight().doubleValue(), ""))
                        .toList();
        return EvaluationOutcome.scored(score, "Summary.", keyPoints, claims, flags, List.of(), criteria);
    }
}
