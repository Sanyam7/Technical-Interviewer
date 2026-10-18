package com.techinterviewer.interview.engine;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.interview.domain.DifficultyBand;
import com.techinterviewer.interview.domain.InterviewPlan;
import com.techinterviewer.interview.domain.QuestionKind;
import com.techinterviewer.interview.domain.QuestionType;
import com.techinterviewer.interview.domain.Section;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The facts about the question (and answer) being processed, as stored when the question was asked.
 *
 * @param answerId           the answer; claims found in it refer to it
 * @param questionId         the question
 * @param kind               what the question is
 * @param section            the section it belongs to (a follow-up belongs to the section of its thread)
 * @param topic              {@code skill/topic} key; null for the introduction and for coding or design questions without a topic
 * @param difficulty         level the question was asked at, 1 to 5
 * @param followUpDepth      0 for a root question, 1 or more for a follow-up (up to 2, or 6 in a design thread)
 * @param askedAt            when the question was asked (the start of its section's time budget if it is the section's first)
 * @param expectedKeyPointIds ids of the question's expected key points, most important first
 * @param questionType       the type of a questioning question (a follow-up: of its thread, EXPERIENCE for a claim probe); null
 *                           elsewhere and for questions asked before types existed
 */
public record AnsweredQuestion(
        UUID answerId,
        UUID questionId,
        QuestionKind kind,
        Section section,
        @Nullable String topic,
        int difficulty,
        int followUpDepth,
        Instant askedAt,
        List<String> expectedKeyPointIds,
        @Nullable QuestionType questionType) {

    public AnsweredQuestion {
        Require.nonNull(answerId, "answerId");
        Require.nonNull(questionId, "questionId");
        Require.nonNull(kind, "kind");
        Require.nonNull(section, "section");
        topic = Require.nullOrNotBlank(topic, "topic", 160);
        Require.between(difficulty, DifficultyBand.LOWEST, DifficultyBand.HIGHEST, "difficulty");
        Require.nonNull(section, "section");
        Require.between(followUpDepth, 0, section == Section.SYSTEM_DESIGN ? InterviewPlan.MAX_THREAD_DEPTH : InterviewPlan.MAX_FOLLOW_UP_DEPTH,
                "followUpDepth");
        Require.nonNull(askedAt, "askedAt");
        expectedKeyPointIds = Immutables.list(expectedKeyPointIds);
        expectedKeyPointIds.forEach(id -> Require.notBlank(id, "expectedKeyPointIds entry", 40));
        if (questionType != null && section != Section.QUESTIONING) {
            throw new IllegalArgumentException("only a questioning question has a question type");
        }
        switch (kind) {
            case INTRO -> requireRoot(section == Section.INTRODUCTION && topic == null, followUpDepth, "the introduction");
            case TECHNICAL -> requireRoot(section == Section.QUESTIONING, followUpDepth, "a technical question");
            case CODING -> requireRoot(section == Section.CODING, followUpDepth, "a coding question");
            case SYSTEM_DESIGN -> requireRoot(section == Section.SYSTEM_DESIGN, followUpDepth, "a design question");
            case FOLLOW_UP -> {
                if (section == Section.INTRODUCTION || followUpDepth < 1) {
                    throw new IllegalArgumentException("a follow-up belongs to a question section and has a depth of at least 1");
                }
            }
        }
    }

    /** A question without a question type. */
    public AnsweredQuestion(UUID answerId, UUID questionId, QuestionKind kind, Section section, @Nullable String topic, int difficulty,
            int followUpDepth, Instant askedAt, List<String> expectedKeyPointIds) {
        this(answerId, questionId, kind, section, topic, difficulty, followUpDepth, askedAt, expectedKeyPointIds, null);
    }

    private static void requireRoot(boolean consistent, int followUpDepth, String what) {
        if (!consistent || followUpDepth != 0) {
            throw new IllegalArgumentException(what + " is a root question of its own section");
        }
    }

    /** True for every question except a follow-up. */
    public boolean isRoot() {
        return kind != QuestionKind.FOLLOW_UP;
    }
}
