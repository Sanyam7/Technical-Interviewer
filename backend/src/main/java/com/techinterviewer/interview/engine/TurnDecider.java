package com.techinterviewer.interview.engine;

import com.techinterviewer.evaluation.domain.CriterionScore;
import com.techinterviewer.evaluation.domain.KeyPointAssessment;
import com.techinterviewer.evaluation.domain.KeyPointStatus;
import com.techinterviewer.interview.domain.Claim;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.FollowUpMode;
import com.techinterviewer.interview.domain.InterviewDifficulty;
import com.techinterviewer.interview.domain.InterviewEvent;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewStage;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.PendingStep;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.domain.SectionProgress;
import com.techinterviewer.interview.domain.TopicStat;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Decides, after an answer has been processed, what the interview does next. This is the deterministic core of a turn
 * (decision D9, AI_ARCHITECTURE.md section 2.9): it updates the memory ({@link MemoryUpdater}), applies the deadline
 * and the candidate's wish to end, asks the {@link FollowUpPolicy}, checks the section's slots and time, picks the next
 * topic and level ({@link TopicSelector}, {@link DifficultyAdapter}) and takes the matching edge of the
 * {@link InterviewStateMachine}. No model output ever picks a transition: the model's ratings are evidence, the
 * decision is made here.
 *
 * <p>Order of precedence: the candidate ending the interview (END_REQUESTED), the deadline having passed (TIME_UP),
 * then the normal flow. An answer that decides the end is still evaluated and counted: the decision is about what
 * follows it.
 *
 * <p>Time: a section asks a new root question only while it can still be answered ({@link DeadlinePolicy#timeToStart});
 * a section that cannot start any more is skipped, and when no planned section can start, or the deadline cuts the last
 * section short of slots its own budget still had time for, the interview ends with TIME_UP before the deadline (the time left would not hold a
 * question worth asking). It ends with PLAN_COMPLETE when the plan ran: every slot asked, or every section's own budget
 * used.
 *
 * <p>Claim probes: the document allows one claim probe per interview section, but the memory does not record the
 * section of a probed claim, so the engine caps the number of probed claims at the number of question sections
 * started (a probe is allowed while {@code probed claims < started question sections}).
 *
 * <p>Résumé: a questioning question may be anchored on an experience of the candidate's résumé ({@link ResumeAnchoring}); the
 * anchor never changes the topic or the level.
 *
 * <p>Stages (AI_ARCHITECTURE.md section 2.15): when the plan's questioning section has stages, {@link StagedQuestioning} decides
 * the stage, type, topic and anchor of each main question, and only the PROJECT stage is anchored on the résumé. In such a section
 * a claim probe is asked only in a thread about the candidate's experience (EXPERIENCE): a fundamentals or practical thread is
 * deepened or probed on its own subject, never turned into a discussion of the candidate's projects. A plan without stages (stored
 * before they existed) is asked as before.
 *
 * <p>Every decision carries its {@link TurnDecision#rationale() rationale}: why the follow-up, the level and the next step
 * are what they are, in words a reviewer can check against the rules.
 */
public final class TurnDecider {

    private TurnDecider() {
    }

    /**
     * @throws IllegalTransitionException when the state does not accept answers or the plan does not fit the state
     *                                    (for example no section after the introduction)
     * @throws IllegalArgumentException   when the facts contradict each other (a follow-up answered in QUESTIONING, a section missing from the plan)
     */
    public static TurnDecision decide(TurnInput input) {
        InterviewState state = input.state();
        if (!state.acceptsAnswers()) {
            throw new IllegalTransitionException(state, InterviewEvent.ANSWER_EVALUATED, "the state does not accept answers");
        }
        requireConsistent(input);
        InterviewMemory updated = MemoryUpdater.apply(
                input.memory(), input.answered(), input.outcome(), input.plan().difficulty(), input.repeatedAnswer());
        Why why = new Why(input, updated);
        if (input.endRequested()) {
            return ending(input.state(), updated, InterviewEvent.END_REQUESTED, List.of(), why.next("end: the candidate ended the interview"));
        }
        if (DeadlinePolicy.isExpired(input.now(), input.deadline())) {
            return ending(input.state(), updated, InterviewEvent.TIME_UP, List.of(), why.next("end: the deadline has passed"));
        }
        if (state == InterviewState.INTRODUCTION) {
            return afterIntroduction(input, updated, why);
        }
        if (state == InterviewState.FOLLOW_UP) {
            return afterFollowUpAnswer(input, updated, why);
        }
        return afterRootAnswer(input, updated, why);
    }

    private static void requireConsistent(TurnInput input) {
        InterviewState state = input.state();
        AnsweredQuestion answered = input.answered();
        Section threadSection = input.resumeSection();
        boolean consistent = switch (state) {
            case INTRODUCTION -> answered.kind() == QuestionKind.INTRO && threadSection == null;
            case FOLLOW_UP -> answered.kind() == QuestionKind.FOLLOW_UP && threadSection != null && answered.section() == threadSection;
            default -> answered.kind() != QuestionKind.FOLLOW_UP
                    && threadSection == null
                    && answered.section().state() == state;
        };
        if (!consistent) {
            throw new IllegalArgumentException("a " + answered.kind() + " question of section " + answered.section()
                    + " cannot be answered in state " + state + " with resume section " + threadSection);
        }
        if (input.plan().section(answered.section()).isEmpty()) {
            throw new IllegalArgumentException("the plan has no " + answered.section() + " section");
        }
    }

    private static TurnDecision ending(
            InterviewState from, InterviewMemory updated, InterviewEvent event, List<StateTransition> before, Why why) {
        List<StateTransition> transitions = new ArrayList<>(before);
        transitions.add(InterviewStateMachine.transition(from, event, TransitionGuards.NONE));
        return new TurnDecision(transitions, null, updated.withPendingStep(null), why.lines());
    }

    private static TurnDecision afterIntroduction(TurnInput input, InterviewMemory updated, Why why) {
        Section first = input.plan().sectionAfter(Section.INTRODUCTION).orElseThrow(() -> new IllegalTransitionException(
                InterviewState.INTRODUCTION, InterviewEvent.INTRO_ANSWERED, "the plan has no section after the introduction"));
        why.put("followUp", "none: the introduction is not followed up");
        Section next = firstStartable(input, updated, first, why);
        if (next == null) {
            return ending(InterviewState.INTRODUCTION, updated, InterviewEvent.TIME_UP, List.of(), why);
        }
        StateTransition taken = InterviewStateMachine.transition(
                InterviewState.INTRODUCTION, InterviewEvent.INTRO_ANSWERED, TransitionGuards.NONE.withNextSection(next));
        return continuing(List.of(taken), updated, nextTopicStep(input, updated, next, why), why);
    }

    /**
     * The next main question and, in a staged questioning section, the stage whose slot it uses.
     *
     * @param stage the stage the question counts against; null outside a staged questioning section
     */
    private record Next(PendingStep step, @Nullable InterviewStage stage) {
    }

    /** A root question of QUESTIONING, CODING or SYSTEM_DESIGN was answered. */
    private static TurnDecision afterRootAnswer(TurnInput input, InterviewMemory updated, Why why) {
        InterviewState state = input.state();
        Section section = input.answered().section();
        FollowUpDecision followUp = decideFollowUp(input, updated, section, why);
        if (followUp.asksFollowUp()) {
            StateTransition taken = InterviewStateMachine.transition(
                    state, InterviewEvent.ANSWER_EVALUATED, TransitionGuards.NONE.withFollowUpAllowed(true));
            return followUpStep(input, updated, followUp, List.of(taken), section, why);
        }
        if (!isSectionComplete(input, updated, section, why)) {
            StateTransition taken = InterviewStateMachine.transition(
                    state, InterviewEvent.ANSWER_EVALUATED, TransitionGuards.NONE.withSlotsRemain(true));
            return continuing(List.of(taken), updated, nextTopicStep(input, updated, section, why), why);
        }
        return completingSection(input, updated, state, section, List.of(), why);
    }

    /** A follow-up question was answered: another follow-up, or the thread ends and the section resumes. */
    private static TurnDecision afterFollowUpAnswer(TurnInput input, InterviewMemory updated, Why why) {
        Section resume = input.resumeSection();
        FollowUpDecision followUp = decideFollowUp(input, updated, resume, why);
        if (followUp.asksFollowUp()) {
            StateTransition taken = InterviewStateMachine.transition(InterviewState.FOLLOW_UP, InterviewEvent.ANSWER_EVALUATED,
                    TransitionGuards.NONE.withFollowUpAllowed(true).withResumeSection(resume));
            return followUpStep(input, updated, followUp, List.of(taken), resume, why);
        }
        StateTransition done = InterviewStateMachine.transition(
                InterviewState.FOLLOW_UP, InterviewEvent.FOLLOW_UP_DONE, TransitionGuards.NONE.withResumeSection(resume));
        if (!isSectionComplete(input, updated, resume, why)) {
            return continuing(List.of(done), updated, nextTopicStep(input, updated, resume, why), why);
        }
        return completingSection(input, updated, done.to(), resume, List.of(done), why);
    }

    /**
     * The section is used up: SECTION_COMPLETE into the next section that can still start (skipping those that cannot), into
     * FINAL_EVALUATION after the last section, or TIME_UP when sections remain but none of them can start.
     */
    private static TurnDecision completingSection(
            TurnInput input, InterviewMemory updated, InterviewState state, Section section, List<StateTransition> before, Why why) {
        Section after = input.plan().sectionAfter(section).orElse(null);
        List<StateTransition> transitions = new ArrayList<>(before);
        if (after == null) {
            Duration remaining = DeadlinePolicy.remaining(input.now(), input.deadline());
            Duration needed = DeadlinePolicy.timeToStart(input.plan(), section);
            if (slotsLeft(input.plan(), updated, section) && remaining.compareTo(needed) < 0
                    && ownBudgetLeft(input.plan(), updated, section, input.now()).compareTo(needed) >= 0) {
                why.next("end: time is up, " + Why.duration(remaining) + " left in the interview, a new " + section + " question needs "
                        + Why.duration(needed));
                return ending(state, updated, InterviewEvent.TIME_UP, before, why);
            }
            transitions.add(InterviewStateMachine.transition(state, InterviewEvent.SECTION_COMPLETE, TransitionGuards.NONE));
            why.next("end: every planned section has run");
            return new TurnDecision(transitions, null, updated.withPendingStep(null), why.lines());
        }
        Section next = firstStartable(input, updated, after, why);
        if (next == null) {
            return ending(state, updated, InterviewEvent.TIME_UP, before, why);
        }
        transitions.add(InterviewStateMachine.transition(
                state, InterviewEvent.SECTION_COMPLETE, TransitionGuards.NONE.withNextSection(next)));
        return continuing(transitions, updated, nextTopicStep(input, updated, next, why), why);
    }

    /** The first section from {@code from} on that can still start a question; null when none can (the reasons are noted). */
    private static @Nullable Section firstStartable(TurnInput input, InterviewMemory memory, Section from, Why why) {
        InterviewPlan plan = input.plan();
        List<String> skipped = new ArrayList<>();
        for (Section section = from; section != null; section = plan.sectionAfter(section).orElse(null)) {
            if (DeadlinePolicy.canStartQuestion(memory, plan, section, input.now(), input.deadline())) {
                if (!skipped.isEmpty()) {
                    why.put("skipped", String.join("; ", skipped));
                }
                return section;
            }
            skipped.add(section + " skipped: " + Why.duration(DeadlinePolicy.timeLeftInSection(memory, plan, section, input.now(),
                    input.deadline())) + " left for it, a question needs " + Why.duration(DeadlinePolicy.timeToStart(plan, section)));
        }
        why.next("end: time is up, no planned section can still start (" + String.join("; ", skipped) + ")");
        return null;
    }

    private static TurnDecision continuing(List<StateTransition> transitions, InterviewMemory updated, Next next, Why why) {
        PendingStep step = next.step();
        InterviewMemory memory = step.resumeItemId() == null ? updated : updated.withResumeItemAsked(step.resumeItemId());
        if (next.stage() != null) {
            memory = memory.withStageQuestion(next.stage());
        }
        return new TurnDecision(transitions, step, memory.withPendingStep(step), why.lines());
    }

    private static TurnDecision followUpStep(
            TurnInput input,
            InterviewMemory updated,
            FollowUpDecision followUp,
            List<StateTransition> transitions,
            Section section,
            Why why) {
        AnsweredQuestion answered = input.answered();
        InterviewMemory memory = updated;
        if (followUp.targetClaim() != null) {
            memory = MemoryUpdater.markClaimProbed(updated, followUp.targetClaim());
        }
        int difficulty = followUp.mode() == FollowUpMode.DEEPEN
                ? deeperDifficulty(input)
                : answered.difficulty();
        PendingStep step = followUp.mode() == FollowUpMode.PROBE_DIMENSION
                ? PendingStep.designProbe(difficulty, answered.questionId(), followUp.targetCriterion())
                : PendingStep.followUp(section, answered.topic(), difficulty, answered.questionId(),
                        followUp.mode(), followUp.targetKeyPointId(), followUp.targetClaim());
        String subject = followUp.targetCriterion() != null ? "the design's " + followUp.targetCriterion()
                : answered.topic() != null ? answered.topic() : section.name();
        why.next("follow-up " + followUp.mode() + " on " + subject + " at level " + Why.level(difficulty));
        return new TurnDecision(transitions, step, memory.withPendingStep(step), why.lines());
    }

    private static FollowUpDecision decideFollowUp(TurnInput input, InterviewMemory updated, Section section, Why why) {
        if (section == Section.SYSTEM_DESIGN) {
            return decideDesignProbe(input, updated, why);
        }
        AnsweredQuestion answered = input.answered();
        List<Claim> unprobed = updated.candidateClaims().stream()
                .filter(claim -> !claim.probed() && claim.sourceAnswerId().equals(answered.answerId()))
                .toList();
        FollowUpDecision decision = FollowUpPolicy.decide(new FollowUpInput(
                input.outcome().score(),
                answered.followUpDepth(),
                input.plan().maxFollowUpDepth(),
                DeadlinePolicy.timeLeftInSection(updated, input.plan(), section, input.now(), input.deadline()),
                keyPointResults(answered, input.outcome().keyPoints()),
                unprobed.stream().map(Claim::text).toList(),
                probedClaims(updated) < startedQuestionSections(updated) && claimProbeFits(input.plan(), answered),
                deeperDifficulty(input) > answered.difficulty(),
                input.repeatedAnswer(),
                DeadlinePolicy.isClosing(input.plan(), input.now(), input.deadline()),
                reservedForBreadth(input.plan(), updated, section)));
        String target = decision.targetKeyPointId() != null ? " " + decision.targetKeyPointId() : "";
        why.put("followUp", (decision.asksFollowUp() ? decision.mode() + target : "MOVE_ON") + ": " + decision.reason().description());
        return decision;
    }

    /** A design thread is followed up only by dimension probes (AI_ARCHITECTURE.md section 2.12). */
    private static FollowUpDecision decideDesignProbe(TurnInput input, InterviewMemory updated, Why why) {
        AnsweredQuestion answered = input.answered();
        DesignProbePolicy.Choice choice = DesignProbePolicy.decide(new DesignProbePolicy.Input(
                input.outcome().score(),
                input.outcome().criteria(),
                input.probedCriteria(),
                answered.followUpDepth(),
                input.plan().maxDepthIn(Section.SYSTEM_DESIGN),
                DeadlinePolicy.timeLeftInSection(updated, input.plan(), Section.SYSTEM_DESIGN, input.now(), input.deadline()),
                input.repeatedAnswer()));
        FollowUpDecision decision = choice.decision();
        if (decision.asksFollowUp()) {
            CriterionScore target = choice.target();
            why.put("followUp", decision.mode() + " " + target.criterion() + ": rated " + target.rating() + " of 4 with weight "
                    + Why.number(target.weight()) + ", shortfall " + choice.shortfall().stripTrailingZeros().toPlainString() + "; "
                    + decision.reason().description()
                    + (input.probedCriteria().isEmpty() ? "" : " (probed already: " + input.probedCriteria() + ")"));
        } else {
            why.put("followUp", "MOVE_ON: " + decision.reason().description());
        }
        return decision;
    }

    /**
     * Questioning time kept for the skills that have not been asked yet: one root question each (the section's minutes per
     * slot), for as many of them as there are slots left. Zero outside questioning.
     */
    static Duration reservedForBreadth(InterviewPlan plan, InterviewMemory memory, Section section) {
        if (section != Section.QUESTIONING) {
            return Duration.ZERO;
        }
        SectionPlan questioning = plan.section(Section.QUESTIONING).orElseThrow();
        Set<String> askedSkills = memory.topicStats().entrySet().stream()
                .filter(entry -> entry.getValue().asked() > 0)
                .map(entry -> TopicSelector.skillOf(entry.getKey()))
                .collect(Collectors.toSet());
        long unasked = questioning.topicQuotas().keySet().stream()
                .map(TopicSelector::skillOf)
                .distinct()
                .filter(skill -> !askedSkills.contains(skill))
                .count();
        SectionProgress progress = memory.sectionProgress().get(Section.QUESTIONING);
        int slotsLeft = questioning.slots() - (progress == null ? 0 : progress.slotsUsed());
        long reserved = Math.min(unasked, Math.max(0, slotsLeft));
        int minutesPerQuestion = Math.max(1, questioning.minutes() / Math.max(1, questioning.slots()));
        return Duration.ofMinutes(reserved * minutesPerQuestion);
    }

    /** Statuses of the question's expected key points, in their order; a key point the evaluator did not judge counts as missing. */
    private static List<KeyPointResult> keyPointResults(AnsweredQuestion answered, List<KeyPointAssessment> assessments) {
        List<KeyPointResult> results = new ArrayList<>();
        for (String id : answered.expectedKeyPointIds()) {
            KeyPointStatus status = assessments.stream()
                    .filter(assessment -> assessment.keyPointId().equals(id))
                    .map(KeyPointAssessment::status)
                    .findFirst()
                    .orElse(KeyPointStatus.MISSING);
            results.add(new KeyPointResult(id, status));
        }
        return results;
    }

    /** One step above the answered question, inside the band and the topic's range. */
    private static int deeperDifficulty(TurnInput input) {
        AnsweredQuestion answered = input.answered();
        DifficultyBand band = input.plan().difficulty();
        int harder = band.clamp(answered.difficulty() + 1);
        return answered.topic() == null ? harder : rangeOf(input.topicRanges(), answered.topic()).clamp(harder);
    }

    /**
     * In a staged questioning section a claim probe only fits a thread about the candidate's experience; elsewhere (other sections,
     * plans without stages) it fits as before.
     */
    private static boolean claimProbeFits(InterviewPlan plan, AnsweredQuestion answered) {
        boolean staged = answered.section() == Section.QUESTIONING
                && plan.section(Section.QUESTIONING).map(SectionPlan::staged).orElse(false);
        return !staged || answered.questionType() == QuestionType.EXPERIENCE;
    }

    private static long probedClaims(InterviewMemory memory) {
        return memory.candidateClaims().stream().filter(Claim::probed).count();
    }

    private static long startedQuestionSections(InterviewMemory memory) {
        return memory.sectionProgress().keySet().stream().filter(section -> section != Section.INTRODUCTION).count();
    }

    /** What the section's own soft budget has left, ignoring the deadline: zero or less when the plan's time for it is used. */
    private static Duration ownBudgetLeft(InterviewPlan plan, InterviewMemory memory, Section section, Instant now) {
        SectionProgress progress = memory.sectionProgress().get(section);
        Duration minutes = Duration.ofMinutes(plan.section(section).orElseThrow().minutes());
        return progress == null ? minutes : Duration.between(now, progress.startedAt().plus(minutes));
    }

    private static boolean slotsLeft(InterviewPlan plan, InterviewMemory memory, Section section) {
        SectionProgress progress = memory.sectionProgress().get(section);
        return (progress == null ? 0 : progress.slotsUsed()) < plan.section(section).orElseThrow().slots();
    }

    /** The section has no slot left, or not enough time for another root question. */
    private static boolean isSectionComplete(TurnInput input, InterviewMemory updated, Section section, Why why) {
        SectionPlan plan = input.plan().section(section).orElseThrow();
        SectionProgress progress = updated.sectionProgress().get(section);
        if (progress.slotsUsed() >= plan.slots()) {
            why.put("section", section + " complete: all " + plan.slots() + " questions asked");
            return true;
        }
        if (!DeadlinePolicy.canStartQuestion(updated, input.plan(), section, input.now(), input.deadline())) {
            why.put("section", section + " complete: " + Why.duration(DeadlinePolicy.timeLeftInSection(updated, input.plan(), section,
                    input.now(), input.deadline())) + " left, a new question needs " + Why.duration(DeadlinePolicy.timeToStart(input.plan(), section))
                    + " (" + progress.slotsUsed() + " of " + plan.slots() + " asked)");
            return true;
        }
        return false;
    }

    /** The first question of a section, or the next one: a topic question under the quotas, or a topic-less coding or design problem. */
    private static Next nextTopicStep(TurnInput input, InterviewMemory memory, Section section, Why why) {
        InterviewPlan plan = input.plan();
        DifficultyBand band = plan.difficulty();
        return switch (section) {
            case QUESTIONING -> {
                SectionPlan questioning = plan.section(Section.QUESTIONING).orElseThrow();
                if (questioning.staged()) {
                    StagedQuestioning.Choice choice = StagedQuestioning.next(plan, input.topicRanges(), memory);
                    why.next(choice.rationale());
                    yield new Next(choice.step(), choice.stage());
                }
                Map<String, Integer> quotas = questioning.topicQuotas();
                TopicChoice choice = TopicSelector.next(quotas, input.topicRanges(), memory, band);
                TopicStat stat = memory.topicStats().get(choice.topic());
                String skill = TopicSelector.skillOf(choice.topic());
                long skillAnswers = memory.topicStats().entrySet().stream()
                        .filter(entry -> TopicSelector.skillOf(entry.getKey()).equals(skill))
                        .mapToInt(entry -> entry.getValue().asked())
                        .sum();
                int asked = stat == null ? 0 : stat.asked();
                PendingStep step = PendingStep.nextTopic(Section.QUESTIONING, choice.topic(), choice.difficulty());
                ResumeAnchoring.Anchor anchor = ResumeAnchoring.pick(plan.resume(), memory.resumeItemsAsked(), choice.topic()).orElse(null);
                why.next("QUESTIONING " + choice.topic() + " at level " + Why.level(choice.difficulty()) + ": skill " + skill + " has "
                        + skillAnswers + " answer(s), topic asked " + asked + " of " + quotas.get(choice.topic())
                        + (stat == null ? "; new topic, starts at the candidate's level" : "; returns at the topic's level")
                        + (anchor == null ? "" : "; anchored on résumé experience " + anchor.itemId() + " (" + anchor.basis() + ")"));
                yield new Next(anchor == null ? step : step.anchoredOn(anchor.itemId()), null);
            }
            case CODING, SYSTEM_DESIGN -> {
                int level = DifficultyAdapter.startLevel(memory, band);
                why.next(section + " problem at level " + Why.level(level) + ": the candidate's level");
                yield new Next(PendingStep.nextTopic(section, null, level), null);
            }
            case INTRODUCTION -> throw new IllegalArgumentException("no question section leads into the introduction");
        };
    }

    private static TopicRange rangeOf(Map<String, TopicRange> ranges, String topic) {
        TopicRange range = ranges.get(topic);
        if (range == null) {
            throw new IllegalArgumentException("no difficulty range for topic " + topic);
        }
        return range;
    }

    /** The rationale of one turn, as short lines in a fixed order of aspects. */
    private static final class Why {

        private final Map<String, String> lines = new LinkedHashMap<>();

        Why(TurnInput input, InterviewMemory updated) {
            lines.put("performance", performance(input, updated));
            lines.put("difficulty", difficulty(input, updated));
            lines.put("followUp", "");
            lines.put("next", "");
            lines.put("time", time(input, updated));
        }

        void put(String aspect, String line) {
            lines.put(aspect, line);
        }

        Why next(String line) {
            lines.put("next", line);
            return this;
        }

        Map<String, String> lines() {
            Map<String, String> filled = new LinkedHashMap<>();
            lines.forEach((aspect, line) -> {
                if (!line.isEmpty()) {
                    filled.put(aspect, line);
                }
            });
            return filled;
        }

        private static String performance(TurnInput input, InterviewMemory updated) {
            AnsweredQuestion answered = input.answered();
            EvaluationOutcome outcome = input.outcome();
            String subject = answered.topic() != null ? answered.topic() : answered.section().name();
            StringBuilder line = new StringBuilder(subject).append(": ").append(switch (outcome.status()) {
                case SCORED -> "score " + number(outcome.score());
                case SKIPPED -> answered.isRoot() ? "skipped, counts as 0" : "skipped follow-up, no evidence";
                case PENDING -> "evaluation pending, no evidence";
            });
            if (input.repeatedAnswer()) {
                line.append(", repeats an earlier answer");
            }
            if (!outcome.misconceptions().isEmpty()) {
                line.append(", ").append(outcome.misconceptions().size()).append(" factual error(s)");
            }
            TopicStat topic = answered.topic() == null ? null : updated.topicStats().get(answered.topic());
            if (topic != null) {
                line.append("; topic ").append(TopicPerformance.of(topic)).append(" (average ").append(number(topic.ema())).append(')');
            }
            TopicStat overall = updated.overall();
            if (overall != null) {
                line.append("; candidate ").append(TopicPerformance.of(overall)).append(" (average ").append(number(overall.ema()))
                        .append(" over ").append(overall.asked()).append(" answer(s))");
            }
            return line.toString();
        }

        private static String difficulty(TurnInput input, InterviewMemory updated) {
            int start = input.plan().difficulty().start();
            int before = input.memory().currentLevel(start);
            int after = updated.currentLevel(start);
            StringBuilder line = new StringBuilder("candidate ").append(level(before));
            if (after != before) {
                line.append(" -> ").append(level(after));
            } else {
                line.append(", unchanged");
            }
            String topic = input.answered().topic();
            if (topic != null && updated.topicStats().containsKey(topic)) {
                TopicStat previous = input.memory().topicStats().get(topic);
                int now = updated.topicStats().get(topic).level();
                line.append("; ").append(topic).append(' ')
                        .append(previous == null ? "first asked at " + input.answered().difficulty() : String.valueOf(previous.level()))
                        .append(" -> ").append(now);
            }
            line.append(" (raise at average >= 75 with last >= 70, lower below 45)");
            if (input.outcome().status() == EvaluationOutcome.Status.SCORED && !input.outcome().reliable()) {
                line.append("; levels held: the evaluation's confidence is LOW, so it moves no level");
            }
            return line.toString();
        }

        private static String time(TurnInput input, InterviewMemory updated) {
            Section section = input.answered().section();
            return duration(DeadlinePolicy.remaining(input.now(), input.deadline())) + " left in the interview; " + section + " has "
                    + duration(DeadlinePolicy.timeLeftInSection(updated, input.plan(), section, input.now(), input.deadline()))
                    + " for new questions" + (DeadlinePolicy.isClosing(input.plan(), input.now(), input.deadline())
                    ? "; closing window (last " + duration(DeadlinePolicy.closingWindow(input.plan())) + ")" : "");
        }

        static String level(int level) {
            return level + " (" + InterviewDifficulty.ofLevel(level) + ")";
        }

        static String duration(Duration duration) {
            long seconds = duration.toSeconds();
            return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
        }

        static String number(double value) {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
    }
}
