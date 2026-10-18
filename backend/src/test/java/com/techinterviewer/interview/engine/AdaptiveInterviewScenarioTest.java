package com.techinterviewer.interview.engine;

import static com.techinterviewer.interview.engine.InterviewSimulation.allMissing;
import static com.techinterviewer.interview.engine.InterviewSimulation.scored;
import static com.techinterviewer.interview.engine.InterviewSimulation.scoredWithClaims;
import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.engine.InterviewSimulation.Answer;
import com.techinterviewer.interview.engine.InterviewSimulation.Asked;
import com.techinterviewer.interview.engine.InterviewSimulation.Candidate;
import com.techinterviewer.interview.engine.InterviewSimulation.Result;
import com.techinterviewer.interview.engine.InterviewSimulation.Turn;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Whole interviews played through the engine with scripted candidates (AI_ARCHITECTURE.md section 2.9): the same
 * candidate always gets the same interview, so each scenario states exactly how the interviewer adapts. The model is
 * not involved: its part (the scores and key-point statuses) is the script.
 */
class AdaptiveInterviewScenarioTest {

    private static final String CONCURRENCY = "java/concurrency";
    private static final String COLLECTIONS = "java/collections";
    private static final String INDEXING = "postgresql/indexing";
    private static final String HOOKS = "react/hooks";
    private static final DifficultyBand WIDE = new DifficultyBand(3, 1, 5);

    /** A 40-minute technical interview: 3 minutes of introduction, 36 of questioning in 6 slots over three skills. */
    private static InterviewPlan technical(int maxFollowUpDepth) {
        return new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 3, 1),
                new SectionPlan(Section.QUESTIONING, 36, 6, Map.of(CONCURRENCY, 2, COLLECTIONS, 1, INDEXING, 2, HOOKS, 1))),
                WIDE, maxFollowUpDepth);
    }

    private static Map<String, TopicRange> ranges(String... topics) {
        Map<String, TopicRange> ranges = new HashMap<>();
        for (String topic : topics) {
            ranges.put(topic, new TopicRange(1, 5));
        }
        return ranges;
    }

    private static final Map<String, TopicRange> RANGES = ranges(CONCURRENCY, COLLECTIONS, INDEXING, HOOKS);

    private static Result play(InterviewPlan plan, int minutes, Candidate candidate) {
        return InterviewSimulation.play(plan, RANGES, Duration.ofMinutes(minutes), candidate);
    }

    private static Answer intro() {
        return Answer.of(EvaluationOutcome.scored(70, "Introduced themselves.", List.of(), List.of(), Set.of()));
    }

    private static Candidate everyAnswer(EvaluationOutcome outcome) {
        return question -> question.kind() == QuestionKind.INTRO ? intro() : Answer.of(outcome);
    }

    private static Map<String, Long> rootsPerTopic(Result result) {
        return result.roots(Section.QUESTIONING).stream().collect(Collectors.groupingBy(Asked::topic, Collectors.counting()));
    }

    // ---- strong, weak, mixed ------------------------------------------------------------------------------------------

    @Test
    void aStrongCandidateIsAskedHarderAndHarderQuestionsAndDeepenedNeverProbedForGaps() {
        Result result = play(technical(2), 40, everyAnswer(scored(92)));

        // The first technical question is at the band's start; the first excellent answer raises the candidate's level, so
        // every new topic after it starts higher, up to the top of the band.
        List<Integer> roots = result.rootDifficulties(Section.QUESTIONING);
        assertThat(roots.getFirst()).as(result.transcript()).isEqualTo(3);
        assertThat(roots.subList(1, roots.size())).as(result.transcript()).isNotEmpty().allMatch(level -> level == 5);
        assertThat(result.followUps()).as(result.transcript()).isNotEmpty()
                .allSatisfy(followUp -> assertThat(followUp.mode()).isEqualTo(FollowUpMode.DEEPEN));
        assertThat(result.followUps()).as("deepening goes one step up each time, never past the band")
                .allSatisfy(followUp -> assertThat(followUp.difficulty()).isBetween(4, 5));

        CandidateState state = result.state();
        assertThat(state.currentDifficulty()).isEqualTo(InterviewDifficulty.HARD);
        assertThat(state.overall()).isEqualTo(TopicPerformance.STRONG);
        assertThat(state.weaknesses()).isEmpty();
        assertThat(state.strengths()).isNotEmpty();
    }

    @Test
    void aWeakCandidateIsNotGroundOnTopicsTheyDoNotKnowTheLevelFallsAndEveryTopicIsStillCovered() {
        Result result = play(technical(2), 40, everyAnswer(allMissing(20)));

        assertThat(result.followUps()).as("below 35 the interviewer moves on").isEmpty();
        assertThat(result.turnsWhere(turn -> turn.question().isRoot() && turn.question().kind() == QuestionKind.TECHNICAL))
                .allSatisfy(turn -> assertThat(turn.decision().rationale().get("followUp"))
                        .isEqualTo("MOVE_ON: " + FollowUpDecision.Reason.TOO_WEAK.description()));
        // New topics start at the candidate's falling level; concurrency returns at its own level, one below the 3 it failed.
        assertThat(result.rootDifficulties(Section.QUESTIONING)).as(result.transcript()).containsExactly(3, 2, 1, 1, 1, 2);
        assertThat(rootsPerTopic(result)).as("breadth is kept: every topic gets its quota")
                .isEqualTo(Map.of(CONCURRENCY, 2L, COLLECTIONS, 1L, INDEXING, 2L, HOOKS, 1L));
        assertThat(result.endReason()).isEqualTo(EndReason.PLAN_COMPLETE);

        CandidateState state = result.state();
        assertThat(state.currentDifficulty()).isEqualTo(InterviewDifficulty.EASY);
        assertThat(state.overall()).isEqualTo(TopicPerformance.WEAK);
        assertThat(state.weaknesses()).containsExactlyInAnyOrder(CONCURRENCY, COLLECTIONS, INDEXING, HOOKS);
        assertThat(state.strengths()).isEmpty();
        assertThat(state.unansweredTopics()).isEmpty();
    }

    @Test
    void aMixedCandidateIsDeepenedWhereStrongProbedWhereHalfRightAndLeftAloneWhereLost() {
        Candidate mixed = question -> {
            if (question.kind() == QuestionKind.INTRO) {
                return intro();
            }
            String skill = TopicSelector.skillOf(question.topic());
            return switch (skill) {
                case "java" -> Answer.of(scored(90));
                case "postgresql" -> Answer.of(allMissing(25));
                default -> Answer.of(scored(60, "kp2"));
            };
        };

        Result result = play(technical(2), 40, mixed);

        Map<String, Set<FollowUpMode>> modesByTopic = new LinkedHashMap<>();
        result.followUps().forEach(followUp -> modesByTopic.computeIfAbsent(followUp.topic(), topic -> new java.util.HashSet<>())
                .add(followUp.mode()));
        assertThat(modesByTopic).as(result.transcript())
                .containsEntry(CONCURRENCY, Set.of(FollowUpMode.DEEPEN))
                .containsEntry(HOOKS, Set.of(FollowUpMode.PROBE_GAP))
                .doesNotContainKey(INDEXING);
        assertThat(result.followUps()).filteredOn(followUp -> followUp.mode() == FollowUpMode.PROBE_GAP)
                .allSatisfy(probe -> assertThat(probe.targetKeyPoint()).isEqualTo("kp2"));

        // Each topic returns at its own level: the second indexing question is easier than the first.
        List<Integer> indexing = result.roots(Section.QUESTIONING).stream().filter(q -> q.topic().equals(INDEXING)).map(Asked::difficulty).toList();
        assertThat(indexing).as(result.transcript()).hasSize(2);
        assertThat(indexing.get(1)).isLessThan(indexing.get(0));

        CandidateState state = result.state();
        assertThat(state.strengths()).contains(CONCURRENCY);
        assertThat(state.weaknesses()).containsExactly(INDEXING);
        assertThat(state.performance(HOOKS)).isEqualTo(TopicPerformance.MIXED);
        assertThat(state.topics().get(CONCURRENCY).level()).isGreaterThan(state.topics().get(INDEXING).level());
    }

    // ---- repeated answers -------------------------------------------------------------------------------------------

    @Test
    void repeatedAnswersAreNeverFollowedUpAndTheInterviewMovesThroughItsPlan() {
        AtomicInteger technicalAnswers = new AtomicInteger();
        Candidate parrot = question -> {
            if (question.kind() == QuestionKind.INTRO) {
                return intro();
            }
            // Half right with a gap: a fresh answer like this is probed. Every answer after the first is the same text.
            Answer answer = Answer.of(scored(55, "kp1"));
            return technicalAnswers.getAndIncrement() == 0 ? answer : answer.repeated();
        };

        Result result = play(technical(2), 40, parrot);

        assertThat(result.followUps()).as("only the first, fresh answer was probed").hasSize(1);
        assertThat(result.turnsWhere(turn -> turn.answer().repeats())).isNotEmpty().allSatisfy(turn -> {
            assertThat(turn.decision().rationale().get("followUp"))
                    .isEqualTo("MOVE_ON: " + FollowUpDecision.Reason.REPEATED_ANSWER.description());
            assertThat(turn.decision().rationale().get("performance")).contains("repeats an earlier answer");
        });
        assertThat(result.memory().flags()).containsEntry(MemoryUpdater.REPEATED_ANSWER_FLAG,
                result.turnsWhere(turn -> turn.answer().repeats()).size());
        assertThat(rootsPerTopic(result)).isEqualTo(Map.of(CONCURRENCY, 2L, COLLECTIONS, 1L, INDEXING, 2L, HOOKS, 1L));
        assertThat(result.endReason()).isEqualTo(EndReason.PLAN_COMPLETE);
    }

    // ---- time ---------------------------------------------------------------------------------------------------------

    @Test
    void withLittleTimeTheInterviewerKeepsBreadthSkipsWhatCannotFitAndEndsEarly() {
        // A 30-minute full loop for a slow candidate: every answer takes 5 minutes and leaves a gap that could be probed.
        InterviewPlan plan = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 2, 1),
                new SectionPlan(Section.QUESTIONING, 12, 3, Map.of(CONCURRENCY, 1, INDEXING, 1, HOOKS, 1)),
                new SectionPlan(Section.CODING, 8, 1),
                new SectionPlan(Section.SYSTEM_DESIGN, 6, 1)), WIDE, 2);
        Candidate slow = question -> (question.kind() == QuestionKind.INTRO ? intro() : Answer.of(scored(60, "kp2")))
                .taking(Duration.ofMinutes(5));

        Result result = play(plan, 30, slow);

        // Questioning starts 5 minutes in. Java at +10: 6 minutes are left, but 8 are kept for the two skills not asked yet,
        // so there is no follow-up. Indexing at +15: 1 minute left, too little for another question: the section ends.
        List<Turn> turns = result.turns();
        assertThat(turns.get(1).question().topic()).as(result.transcript()).isEqualTo(CONCURRENCY);
        assertThat(turns.get(1).decision().rationale().get("followUp"))
                .isEqualTo("MOVE_ON: " + FollowUpDecision.Reason.BREADTH_RESERVE.description());
        assertThat(turns.get(2).question().topic()).isEqualTo(INDEXING);
        assertThat(turns.get(2).decision().rationale().get("section")).startsWith("QUESTIONING complete: 1m00s left");
        // Coding still fits (8 minutes from +15, with the design's 6 reserved). Its gap is probed with the 3 minutes it has left.
        assertThat(turns.get(3).question().kind()).isEqualTo(QuestionKind.CODING);
        assertThat(turns.get(4).question().mode()).isEqualTo(FollowUpMode.PROBE_GAP);
        // At +25 the design would have 5 minutes, less than its 6: it is skipped, and nothing is left to start.
        assertThat(result.endReason()).isEqualTo(EndReason.TIME_UP);
        assertThat(result.endedAt()).as("before the deadline: no question fits the 5 minutes left").isBefore(result.deadline());
        assertThat(result.last().rationale().get("next"))
                .isEqualTo("end: time is up, no planned section can still start (SYSTEM_DESIGN skipped: 5m00s left for it, a question needs 6m00s)");
        assertThat(result.roots(Section.SYSTEM_DESIGN)).isEmpty();

        // No root question was ever started without the time to answer it.
        assertThat(result.asked()).filteredOn(Asked::isRoot).filteredOn(question -> question.kind() != QuestionKind.INTRO)
                .allSatisfy(question -> assertThat(question.sectionTimeLeft())
                        .isGreaterThanOrEqualTo(DeadlinePolicy.timeToStart(plan, question.section())));
        assertThat(result.state().unansweredTopics()).containsExactly(HOOKS);
        assertThat(result.state().sectionsNotStarted()).containsExactly(Section.SYSTEM_DESIGN);
    }

    @Test
    void inTheClosingMinutesAnExcellentAnswerIsNotDeepenedButAGapIsStillProbed() {
        InterviewPlan plan = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 2, 1),
                new SectionPlan(Section.QUESTIONING, 28, 6, Map.of(CONCURRENCY, 3, INDEXING, 3))), WIDE, 2);
        // 30 minutes, closing window 5 minutes. Answers take 4 minutes; the one answered in the closing window is excellent.
        Candidate candidate = question -> {
            if (question.kind() == QuestionKind.INTRO) {
                return intro().taking(Duration.ofMinutes(4));
            }
            boolean closing = !question.at().plus(Duration.ofMinutes(4)).isBefore(EngineFixtures.T0.plus(Duration.ofMinutes(25)));
            return Answer.of(closing ? scored(95) : scored(60, "kp3")).taking(Duration.ofMinutes(4));
        };

        Result result = play(plan, 30, candidate);

        List<Turn> closing = result.turnsWhere(turn -> DeadlinePolicy.isClosing(plan, turn.answeredAt(), result.deadline()));
        assertThat(closing).as(result.transcript()).isNotEmpty();
        assertThat(closing).filteredOn(turn -> turn.decision().rationale().get("followUp") != null)
                .allSatisfy(turn -> assertThat(turn.decision().rationale().get("followUp")).doesNotStartWith("DEEPEN"));
        assertThat(result.followUps()).as("gaps found before the closing window were probed").isNotEmpty()
                .allSatisfy(followUp -> assertThat(followUp.mode()).isEqualTo(FollowUpMode.PROBE_GAP));
    }

    // ---- balance ------------------------------------------------------------------------------------------------------

    @Test
    void followUpsNeverTakeTheTimeOfSkillsNotAskedYetSoNoTopicTakesTheSection() {
        // Twelve minutes for three skills: a candidate whose every answer has a gap would be probed on the first topic until
        // the section ran out. The reserve keeps four minutes per skill not yet asked.
        InterviewPlan plan = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 2, 1),
                new SectionPlan(Section.QUESTIONING, 12, 3, Map.of(CONCURRENCY, 1, INDEXING, 1, HOOKS, 1))), WIDE, 2);

        Result result = play(plan, 15, everyAnswer(scored(60, "kp2")));

        assertThat(rootsPerTopic(result)).as(result.transcript()).isEqualTo(Map.of(CONCURRENCY, 1L, INDEXING, 1L, HOOKS, 1L));
        Map<String, Long> followUps = result.followUps().stream().collect(Collectors.groupingBy(Asked::topic, Collectors.counting()));
        assertThat(followUps.getOrDefault(CONCURRENCY, 0L)).as("the first topic gave its time to the others").isZero();
        assertThat(result.turns().get(1).decision().rationale().get("followUp"))
                .isEqualTo("MOVE_ON: " + FollowUpDecision.Reason.BREADTH_RESERVE.description());
        assertThat(result.followUps()).as("once every skill was asked, the spare time went to probing gaps").isNotEmpty();
    }

    @Test
    void noTopicGetsMoreThanItsQuotaOfMainQuestionsOrMoreFollowUpsThanTheDepthAllows() {
        Result result = play(technical(2), 60, everyAnswer(scored(60, "kp1")));

        assertThat(rootsPerTopic(result)).as(result.transcript())
                .isEqualTo(Map.of(CONCURRENCY, 2L, COLLECTIONS, 1L, INDEXING, 2L, HOOKS, 1L));
        assertThat(result.followUps()).allSatisfy(followUp -> assertThat(followUp.depth()).isBetween(1, 2));
        // Skills take turns: the first three main questions cover the three skills.
        assertThat(result.roots(Section.QUESTIONING).subList(0, 3)).extracting(question -> TopicSelector.skillOf(question.topic()))
                .containsExactlyInAnyOrder("java", "postgresql", "react");
    }

    // ---- follow-ups ---------------------------------------------------------------------------------------------------

    @Test
    void aGapIsProbedAtTheSameLevelAStrongRecoveryIsDeepenedAndOnlyOneClaimIsProbedPerSection() {
        Candidate candidate = question -> {
            if (question.kind() == QuestionKind.INTRO) {
                return intro();
            }
            if (question.kind() == QuestionKind.FOLLOW_UP) {
                return Answer.of(scored(88));
            }
            return switch (TopicSelector.skillOf(question.topic())) {
                case "java" -> Answer.of(scored(60, "kp2"));
                default -> Answer.of(scoredWithClaims(82, List.of("Ran " + question.topic() + " in production at scale")));
            };
        };

        Result result = play(technical(2), 60, candidate);

        List<Asked> asked = result.asked();
        assertThat(asked.get(1).topic()).as(result.transcript()).isEqualTo(CONCURRENCY);
        assertThat(asked.get(2)).satisfies(probe -> {
            assertThat(probe.mode()).isEqualTo(FollowUpMode.PROBE_GAP);
            assertThat(probe.targetKeyPoint()).isEqualTo("kp2");
            assertThat(probe.difficulty()).as("a gap probe stays at the question's level").isEqualTo(asked.get(1).difficulty());
        });
        assertThat(asked.get(3)).satisfies(deeper -> {
            assertThat(deeper.mode()).isEqualTo(FollowUpMode.DEEPEN);
            assertThat(deeper.difficulty()).isEqualTo(asked.get(2).difficulty() + 1);
            assertThat(deeper.depth()).isEqualTo(2);
        });
        assertThat(asked.get(4).isRoot()).as("the depth limit ends the thread").isTrue();
        assertThat(result.followUps()).filteredOn(followUp -> followUp.mode() == FollowUpMode.PROBE_CLAIM)
                .as("one claim probe in the questioning section").hasSize(1);
    }

    // ---- difficulty ---------------------------------------------------------------------------------------------------

    @Test
    void theLevelRisesWithStrongAnswersFallsWithWeakOnesAndCarriesAcrossTopics() {
        // Eight topics of one question each, no follow-ups: every question starts at the candidate's level, so the sequence
        // of levels is the candidate-wide moving average at work. Scores 90, 90, 90, 30, 30, 30, 30, 70:
        //   ema 90 -> 4, 90 -> 5, 90 -> 5 (top of the band), 54 -> 5, 39.6 -> 4, 33.84 -> 3, 31.54 -> 2, 54.62 -> 2.
        Map<String, Integer> quotas = new LinkedHashMap<>();
        for (String skill : List.of("s1", "s2", "s3", "s4")) {
            quotas.put(skill + "/a", 1);
            quotas.put(skill + "/b", 1);
        }
        InterviewPlan plan = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 2, 1), new SectionPlan(Section.QUESTIONING, 40, 8, quotas)), WIDE, 0);
        List<Double> scores = List.of(90.0, 90.0, 90.0, 30.0, 30.0, 30.0, 30.0, 70.0);
        AtomicInteger next = new AtomicInteger();
        Candidate candidate = question -> question.kind() == QuestionKind.INTRO ? intro()
                : Answer.of(scored(scores.get(next.getAndIncrement()))).taking(Duration.ofMinutes(1));

        Result result = InterviewSimulation.play(plan, ranges(quotas.keySet().toArray(String[]::new)), Duration.ofMinutes(45), candidate);

        assertThat(result.rootDifficulties(Section.QUESTIONING)).as(result.transcript()).containsExactly(3, 4, 5, 5, 5, 4, 3, 2);
        assertThat(result.memory().overall().ema()).isEqualTo(54.62);
        assertThat(result.state().currentDifficulty()).isEqualTo(InterviewDifficulty.EASY);
        assertThat(result.turns().get(1).decision().rationale().get("difficulty")).startsWith("candidate 3 (MEDIUM) -> 4 (HARD)");
        assertThat(result.turns().get(5).decision().rationale().get("difficulty")).startsWith("candidate 5 (HARD) -> 4 (HARD)");
    }

    @Test
    void codingAndDesignStartAtTheLevelTheQuestioningEndedOn() {
        InterviewPlan plan = new InterviewPlan(1, List.of(
                new SectionPlan(Section.INTRODUCTION, 3, 1),
                new SectionPlan(Section.QUESTIONING, 20, 3, Map.of(CONCURRENCY, 1, INDEXING, 1, HOOKS, 1)),
                new SectionPlan(Section.CODING, 15, 1),
                new SectionPlan(Section.SYSTEM_DESIGN, 15, 1)), WIDE, 0);

        Result strong = play(plan, 60, everyAnswer(scored(95)));
        Result weak = play(plan, 60, everyAnswer(allMissing(15)));

        assertThat(strong.roots(Section.CODING)).singleElement().extracting(Asked::difficulty).isEqualTo(5);
        assertThat(weak.roots(Section.CODING)).singleElement().extracting(Asked::difficulty).isEqualTo(1);
        assertThat(strong.roots(Section.SYSTEM_DESIGN)).singleElement().extracting(Asked::difficulty).isEqualTo(5);
        assertThat(strong.endReason()).isEqualTo(EndReason.PLAN_COMPLETE);
    }
}
