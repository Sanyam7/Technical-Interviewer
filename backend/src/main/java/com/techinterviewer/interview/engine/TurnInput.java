package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Everything {@link TurnDecider} needs to decide what follows an answer.
 *
 * @param state         the interview state when the answer arrived
 * @param resumeSection the section of the running follow-up thread (the interview's {@code resume_state}); required in FOLLOW_UP, null otherwise
 * @param plan          the interview plan
 * @param memory        the memory before this answer
 * @param topicRanges   difficulty range of every topic named in the plan's quotas and of the answered question's topic
 * @param answered      the question being answered
 * @param outcome       what became of the answer
 * @param now           the current time
 * @param deadline      the interview deadline
 * @param endRequested  the candidate ended the interview with this answer
 * @param repeatedAnswer the answer repeats an earlier answer of the interview (decided by the service, which has the texts)
 * @param probedCriteria the design dimensions the answered question's design thread has probed already (the answered question's own
 *                      included); empty outside a design thread
 */
public record TurnInput(
        InterviewState state,
        @Nullable Section resumeSection,
        InterviewPlan plan,
        InterviewMemory memory,
        Map<String, TopicRange> topicRanges,
        AnsweredQuestion answered,
        EvaluationOutcome outcome,
        Instant now,
        Instant deadline,
        boolean endRequested,
        boolean repeatedAnswer,
        Set<Criterion> probedCriteria) {

    public TurnInput {
        Require.nonNull(state, "state");
        Require.nonNull(plan, "plan");
        Require.nonNull(memory, "memory");
        topicRanges = Map.copyOf(Require.nonNull(topicRanges, "topicRanges"));
        Require.nonNull(answered, "answered");
        Require.nonNull(outcome, "outcome");
        Require.nonNull(now, "now");
        Require.nonNull(deadline, "deadline");
        probedCriteria = Immutables.enumSet(probedCriteria, Criterion.class);
    }

    public TurnInput(InterviewState state, @Nullable Section resumeSection, InterviewPlan plan, InterviewMemory memory,
            Map<String, TopicRange> topicRanges, AnsweredQuestion answered, EvaluationOutcome outcome, Instant now, Instant deadline,
            boolean endRequested, boolean repeatedAnswer) {
        this(state, resumeSection, plan, memory, topicRanges, answered, outcome, now, deadline, endRequested, repeatedAnswer, Set.of());
    }

    public TurnInput(InterviewState state, @Nullable Section resumeSection, InterviewPlan plan, InterviewMemory memory,
            Map<String, TopicRange> topicRanges, AnsweredQuestion answered, EvaluationOutcome outcome, Instant now, Instant deadline,
            boolean endRequested) {
        this(state, resumeSection, plan, memory, topicRanges, answered, outcome, now, deadline, endRequested, false);
    }
}
