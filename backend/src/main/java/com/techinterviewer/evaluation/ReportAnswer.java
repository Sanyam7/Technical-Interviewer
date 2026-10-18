package com.techinterviewer.evaluation;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.AssessmentArea;
import com.techinterviewer.evaluation.domain.EvaluationFlag;
import com.techinterviewer.interview.domain.EvaluationFailure;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.Section;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the report needs to know about one answered question: where it sat in the interview, what it was about and how
 * it fared. The report service builds one per answer row (joined with its question and its evaluation).
 *
 * @param questionId the question
 * @param threadId   the root question of the thread the question belongs to: its own id for a root question, the id of
 *                   the root it follows up for a follow-up (also for a follow-up of a follow-up)
 * @param sequenceNo position of the question in the interview, from 1; orders the turns of a thread
 * @param kind       what the question is
 * @param section    the section of the question; a follow-up has the section of its thread
 * @param topic      {@code skill/topic} key of the question; null for the introduction and for coding or design
 *                   questions without a catalog topic
 * @param difficulty level the question was asked at, 1 to 5
 * @param skipped    the candidate skipped the question
 * @param score      overall score 0 to 100 of the answer's completed evaluation; present exactly when the answer was
 *                   neither skipped nor unassessable (a report needs every answered question evaluated or given up)
 * @param flags      flags of the evaluation; none for a skipped or unassessable answer
 * @param unassessable why the answer could not be assessed, or null. An unassessable answer is neither scored nor treated as
 *                   a skip: it takes no part in the scores and is disclosed in the report
 * @param area         the assessment area the answer counts in (AI_ARCHITECTURE.md section 2.15); null exactly for the introduction
 * @param communicationRating the scored COMMUNICATION rating of the answer's evaluation, 0 to 4; null when it has none (a skipped,
 *                     unassessable or coding answer, a design answer)
 */
public record ReportAnswer(
        UUID questionId,
        UUID threadId,
        int sequenceNo,
        QuestionKind kind,
        Section section,
        @Nullable String topic,
        int difficulty,
        boolean skipped,
        @Nullable Double score,
        Set<EvaluationFlag> flags,
        @Nullable EvaluationFailure unassessable,
        @Nullable AssessmentArea area,
        @Nullable Integer communicationRating) {

    public ReportAnswer {
        Require.nonNull(questionId, "questionId");
        Require.nonNull(threadId, "threadId");
        Require.atLeast(sequenceNo, 1, "sequenceNo");
        Require.nonNull(kind, "kind");
        Require.nonNull(section, "section");
        topic = Require.nullOrNotBlank(topic, "topic", 160);
        if (topic != null && topic.indexOf('/') <= 0) {
            throw new IllegalArgumentException("topic must be a skill/topic key but was " + topic);
        }
        Require.between(difficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "difficulty");
        flags = Immutables.enumSet(flags, EvaluationFlag.class);
        if (skipped && unassessable != null) {
            throw new IllegalArgumentException("a skipped answer cannot be unassessable");
        }
        if (skipped || unassessable != null) {
            if (score != null || !flags.isEmpty()) {
                throw new IllegalArgumentException("a skipped or unassessable answer has no score and no flags");
            }
        } else {
            Require.between(Require.nonNull(score, "score of an answered question"), 0, 100, "score");
        }
        boolean followUp = kind == QuestionKind.FOLLOW_UP;
        if (followUp == threadId.equals(questionId)) {
            throw new IllegalArgumentException(
                    "a follow-up belongs to the thread of another question, every other question is its own thread root");
        }
        boolean consistent = switch (kind) {
            case INTRO -> section == Section.INTRODUCTION && topic == null;
            case TECHNICAL -> section == Section.QUESTIONING;
            case CODING -> section == Section.CODING;
            case SYSTEM_DESIGN -> section == Section.SYSTEM_DESIGN;
            case FOLLOW_UP -> section != Section.INTRODUCTION;
        };
        if (!consistent) {
            throw new IllegalArgumentException("a " + kind + " question cannot belong to the " + section + " section");
        }
        if ((area == null) != (section == Section.INTRODUCTION) || area == AssessmentArea.COMMUNICATION) {
            throw new IllegalArgumentException("every answer but the introduction counts in one area of its questions, not in COMMUNICATION");
        }
        if (communicationRating != null) {
            Require.between(communicationRating, 0, 4, "communicationRating");
            if (score == null) {
                throw new IllegalArgumentException("only a scored answer has a communication rating");
            }
        }
    }

    /** An answer whose area follows from its section alone (no question type), without a communication rating. */
    public ReportAnswer(
            UUID questionId,
            UUID threadId,
            int sequenceNo,
            QuestionKind kind,
            Section section,
            @Nullable String topic,
            int difficulty,
            boolean skipped,
            @Nullable Double score,
            Set<EvaluationFlag> flags,
            @Nullable EvaluationFailure unassessable) {
        this(questionId, threadId, sequenceNo, kind, section, topic, difficulty, skipped, score, flags, unassessable,
                AssessmentArea.of(section, null, false), null);
    }

    /** An answer with a score, or a skipped one: every answer that is not unassessable. */
    public ReportAnswer(
            UUID questionId,
            UUID threadId,
            int sequenceNo,
            QuestionKind kind,
            Section section,
            @Nullable String topic,
            int difficulty,
            boolean skipped,
            @Nullable Double score,
            Set<EvaluationFlag> flags) {
        this(questionId, threadId, sequenceNo, kind, section, topic, difficulty, skipped, score, flags, null);
    }

    /** The skill of the topic; null when the question has no topic. */
    public @Nullable String skill() {
        return topic == null ? null : topic.substring(0, topic.indexOf('/'));
    }

    public boolean isFollowUp() {
        return kind == QuestionKind.FOLLOW_UP;
    }
}
