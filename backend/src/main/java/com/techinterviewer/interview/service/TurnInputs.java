package com.techinterviewer.interview.service;

import com.techinterviewer.catalog.TopicInfo;
import com.techinterviewer.evaluation.domain.Criterion;
import com.techinterviewer.interview.domain.CandidateAnswer;
import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.Interview;
import com.techinterviewer.interview.domain.InterviewQuestion;
import com.techinterviewer.interview.domain.InterviewState;
import com.techinterviewer.interview.domain.Section;
import com.techinterviewer.interview.domain.SectionPlan;
import com.techinterviewer.interview.engine.AnsweredQuestion;
import com.techinterviewer.interview.engine.EvaluationOutcome;
import com.techinterviewer.interview.engine.TopicRange;
import com.techinterviewer.interview.engine.TurnInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Builds the facts the engine decides a turn from; plain functions of the rows, so they can be tested without a database. */
final class TurnInputs {

    private TurnInputs() {
    }

    /** The topic keys the engine needs a difficulty range for: the quotas of the questioning section and the answered topic. */
    static Set<String> rangeKeys(Interview interview, @Nullable String answeredTopic) {
        Set<String> keys = new LinkedHashSet<>();
        interview.getPlan().section(Section.QUESTIONING).map(SectionPlan::topicQuotas).ifPresent(quotas -> keys.addAll(quotas.keySet()));
        if (answeredTopic != null) {
            keys.add(answeredTopic);
        }
        return keys;
    }

    static AnsweredQuestion answered(InterviewQuestion question, CandidateAnswer answer, @Nullable TopicInfo topic) {
        List<String> keyPoints = new ArrayList<>();
        for (ExpectedKeyPoint keyPoint : question.getExpectedKeyPoints()) {
            keyPoints.add(keyPoint.id());
        }
        return new AnsweredQuestion(
                answer.getId(),
                question.getId(),
                question.getKind(),
                question.getSection(),
                topic == null ? null : topic.key(),
                question.getDifficulty(),
                question.getFollowUpDepth(),
                question.getAskedAt(),
                keyPoints,
                question.getQuestionType());
    }

    static TurnInput input(
            Interview interview,
            AnsweredQuestion answered,
            EvaluationOutcome outcome,
            Map<String, TopicRange> ranges,
            Instant now,
            boolean repeatedAnswer,
            Set<Criterion> probedCriteria) {
        InterviewState resume = interview.getResumeState();
        return new TurnInput(
                interview.getState(),
                resume == null ? null : sectionOf(resume),
                interview.getPlan(),
                interview.getMemory(),
                ranges,
                answered,
                outcome,
                now,
                requireDeadline(interview),
                false,
                repeatedAnswer,
                probedCriteria);
    }

    /** The section whose questions are asked in {@code state}. */
    static Section sectionOf(InterviewState state) {
        for (Section section : Section.values()) {
            if (section.state() == state) {
                return section;
            }
        }
        throw new IllegalStateException(state + " is not the state of a section");
    }

    private static Instant requireDeadline(Interview interview) {
        Instant deadline = interview.getDeadlineAt();
        if (deadline == null) {
            throw new IllegalStateException("interview " + interview.getId() + " asks questions but has no deadline");
        }
        return deadline;
    }
}
