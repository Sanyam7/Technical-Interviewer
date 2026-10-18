package com.techinterviewer.interview.engine;

import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.EndReason;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.service.InterviewMemories;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Plays a whole interview through the engine with a scripted candidate and a simulated clock, the way the services drive
 * it: the question is "stored" (its section starts) when it is asked, the answer takes the candidate's time, and the
 * decision is taken on the memory as it is then. Records every question and decision, so a test can read the interview
 * the way a reviewer would read a transcript.
 */
final class InterviewSimulation {

    static final List<String> KEY_POINTS = List.of("kp1", "kp2", "kp3");

    /**
     * A question the interviewer asked, with the time its section had for new questions at that moment, and its type and résumé anchor
     * as the services store them (a follow-up carries its thread's type, a claim probe EXPERIENCE).
     */
    record Asked(int sequence, QuestionKind kind, Section section, @Nullable String topic, int difficulty, int depth,
            @Nullable FollowUpMode mode, @Nullable String targetKeyPoint, Instant at, Duration sectionTimeLeft, @Nullable QuestionType type,
            @Nullable String resumeItem) {

        boolean isRoot() {
            return kind != QuestionKind.FOLLOW_UP;
        }
    }

    /** What the candidate does with a question: the evaluated outcome, whether it repeats an earlier answer, how long it takes. */
    record Answer(EvaluationOutcome outcome, boolean repeats, Duration takes) {

        static Answer of(EvaluationOutcome outcome) {
            return new Answer(outcome, false, Duration.ofMinutes(2));
        }

        Answer taking(Duration time) {
            return new Answer(outcome, repeats, time);
        }

        Answer repeated() {
            return new Answer(outcome, true, takes);
        }
    }

    interface Candidate {
        Answer answer(Asked question);
    }

    /** One answered question and what the engine decided after it. */
    record Turn(Asked question, Answer answer, Instant answeredAt, TurnDecision decision) {
    }

    record Result(InterviewPlan plan, Instant deadline, List<Turn> turns, InterviewMemory memory) {

        List<Asked> asked() {
            return turns.stream().map(Turn::question).toList();
        }

        List<Asked> roots(Section section) {
            return asked().stream().filter(question -> question.isRoot() && question.section() == section).toList();
        }

        List<Asked> followUps() {
            return asked().stream().filter(question -> !question.isRoot()).toList();
        }

        List<Integer> rootDifficulties(Section section) {
            return roots(section).stream().map(Asked::difficulty).toList();
        }

        TurnDecision last() {
            return turns.getLast().decision();
        }

        @Nullable EndReason endReason() {
            return last().endReason();
        }

        Instant endedAt() {
            return turns.getLast().answeredAt();
        }

        CandidateState state() {
            return CandidateState.of(memory, plan, endedAt(), deadline);
        }

        List<Turn> turnsWhere(Predicate<Turn> condition) {
            return turns.stream().filter(condition).toList();
        }

        /** The transcript in one line per turn, for assertion messages. */
        String transcript() {
            StringBuilder text = new StringBuilder();
            for (Turn turn : turns) {
                Asked q = turn.question();
                text.append(q.sequence()).append(' ').append(q.kind()).append(q.type() == null ? "" : " " + q.type())
                        .append(q.mode() == null ? "" : " " + q.mode())
                        .append(' ').append(q.topic() == null ? q.section() : q.topic()).append(" @").append(q.difficulty())
                        .append(" -> ").append(turn.decision().rationale()).append('\n');
            }
            return text.toString();
        }
    }

    private InterviewSimulation() {
    }

    static Result play(InterviewPlan plan, Map<String, TopicRange> ranges, Duration duration, Candidate candidate) {
        Instant deadline = DeadlinePolicy.deadline(EngineFixtures.T0, (int) duration.toMinutes());
        Instant now = EngineFixtures.T0;
        InterviewState state = InterviewState.INTRODUCTION;
        Section resume = null;
        InterviewMemory memory = InterviewMemories.withSectionStarted(InterviewMemory.empty(), Section.INTRODUCTION, now);
        Asked current = new Asked(1, QuestionKind.INTRO, Section.INTRODUCTION, null, plan.difficulty().start(), 0, null, null, now,
                DeadlinePolicy.timeLeftInSection(memory, plan, Section.INTRODUCTION, now, deadline), null, null);
        List<Turn> turns = new ArrayList<>();
        for (int guard = 0; guard < 200; guard++) {
            Answer answer = candidate.answer(current);
            now = now.plus(answer.takes());
            AnsweredQuestion answered = new AnsweredQuestion(EngineFixtures.answer(current.sequence()), EngineFixtures.question(current.sequence()),
                    current.kind(), current.section(), current.topic(), current.difficulty(), current.depth(), current.at(),
                    current.kind() == QuestionKind.INTRO ? List.of() : KEY_POINTS, current.type());
            TurnDecision decision = TurnDecider.decide(new TurnInput(
                    state, resume, plan, memory, ranges, answered, answer.outcome(), now, deadline, false, answer.repeats()));
            turns.add(new Turn(current, answer, now, decision));
            memory = decision.memory();
            state = decision.nextState();
            resume = decision.resumeSection();
            if (decision.interviewComplete()) {
                return new Result(plan, deadline, turns, memory);
            }
            PendingStep step = decision.pendingStep();
            // The question is stored now: its section starts when its first question is asked.
            memory = InterviewMemories.withSectionStarted(memory, step.section(), now);
            boolean followUp = step.kind() == PendingStep.Kind.FOLLOW_UP;
            QuestionType type = !followUp ? step.questionType()
                    : current.type() == null ? null
                    : step.followUpMode() == FollowUpMode.PROBE_CLAIM ? QuestionType.EXPERIENCE : current.type();
            current = new Asked(current.sequence() + 1,
                    followUp ? QuestionKind.FOLLOW_UP : rootKind(step.section()),
                    step.section(), step.topic(), step.difficulty(), followUp ? current.depth() + 1 : 0, step.followUpMode(),
                    step.targetKeyPointId(), now, DeadlinePolicy.timeLeftInSection(memory, plan, step.section(), now, deadline), type,
                    step.resumeItemId());
        }
        throw new AssertionError("the interview did not end");
    }

    private static QuestionKind rootKind(Section section) {
        return switch (section) {
            case QUESTIONING -> QuestionKind.TECHNICAL;
            case CODING -> QuestionKind.CODING;
            case SYSTEM_DESIGN -> QuestionKind.SYSTEM_DESIGN;
            case INTRODUCTION -> throw new AssertionError("nothing leads back into the introduction");
        };
    }

    // ---- outcomes ----------------------------------------------------------------------------------------------------

    /** A scored answer: every key point COVERED except the listed MISSING ones. */
    static EvaluationOutcome scored(double score, String... missing) {
        return scoredWithClaims(score, List.of(), missing);
    }

    static EvaluationOutcome scoredWithClaims(double score, List<String> claims, String... missing) {
        List<KeyPointAssessment> keyPoints = new ArrayList<>();
        for (String id : KEY_POINTS) {
            keyPoints.add(EngineFixtures.keyPoint(id, List.of(missing).contains(id) ? KeyPointStatus.MISSING : KeyPointStatus.COVERED));
        }
        return EvaluationOutcome.scored(score, "Summary of the answer.", keyPoints, claims, Set.of());
    }

    static EvaluationOutcome allMissing(double score) {
        return scored(score, KEY_POINTS.toArray(String[]::new));
    }
}
