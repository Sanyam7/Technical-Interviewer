package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One-line memory of an answered question, so later prompts need no transcript.
 *
 * @param questionId the question
 * @param topic      {@code skill/topic} key; absent for the introduction
 * @param summary    one sentence from the evaluator
 * @param score      0 to 100
 */
public record AnswerSummary(UUID questionId, @Nullable String topic, String summary, double score) {

    public static final int MAX_LENGTH = 500;

    public AnswerSummary {
        Require.nonNull(questionId, "questionId");
        topic = Require.nullOrNotBlank(topic, "topic", 160);
        Require.notBlank(summary, "summary", MAX_LENGTH);
        Require.between(score, 0, 100, "score");
    }
}
