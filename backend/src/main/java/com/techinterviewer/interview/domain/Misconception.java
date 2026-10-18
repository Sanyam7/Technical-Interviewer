package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;
import org.jspecify.annotations.Nullable;

/**
 * Something the candidate stated that the evaluator marked as a factual error (the claim, not the correction: the
 * correction is for the report, the memory only needs to know what the candidate believes).
 *
 * @param claim the incorrect statement, as the evaluator quoted it
 * @param topic {@code skill/topic} key of the question it was made in; null for coding and design questions without a topic
 */
public record Misconception(String claim, @Nullable String topic) {

    public static final int MAX_LENGTH = 300;

    public Misconception {
        Require.notBlank(claim, "claim", MAX_LENGTH);
        topic = Require.nullOrNotBlank(topic, "topic", 160);
    }
}
