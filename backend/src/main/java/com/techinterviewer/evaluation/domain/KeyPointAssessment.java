package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.validation.Require;
import org.jspecify.annotations.Nullable;

/**
 * How one expected key point fared in an answer, stored in {@code evaluations.key_points}.
 *
 * @param keyPointId    id of the {@code ExpectedKeyPoint}
 * @param status        covered, partly covered or missing
 * @param evidenceQuote the words of the answer that show it (absent when missing)
 * @param verified      true when the server confirmed that the quote really occurs in the answer
 */
public record KeyPointAssessment(
        String keyPointId, KeyPointStatus status, @Nullable String evidenceQuote, boolean verified) {

    public static final int MAX_QUOTE_LENGTH = 1000;

    public KeyPointAssessment {
        Require.notBlank(keyPointId, "keyPointId", 40);
        Require.nonNull(status, "status");
        evidenceQuote = Require.nullOrNotBlank(evidenceQuote, "evidenceQuote", MAX_QUOTE_LENGTH);
    }
}
