package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;
import java.util.UUID;

/**
 * A statement the candidate made about their own experience ("migrated a monolith to Kafka"), kept so a
 * follow-up can probe it once.
 *
 * @param text           the claim in the evaluator's words
 * @param probed         whether a follow-up has already probed it
 * @param sourceAnswerId the answer it came from
 */
public record Claim(String text, boolean probed, UUID sourceAnswerId) {

    public static final int MAX_LENGTH = 300;

    public Claim {
        Require.notBlank(text, "text", MAX_LENGTH);
        Require.nonNull(sourceAnswerId, "sourceAnswerId");
    }

    public Claim asProbed() {
        return new Claim(text, true, sourceAnswerId);
    }
}
