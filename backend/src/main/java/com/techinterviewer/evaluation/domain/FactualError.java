package com.techinterviewer.evaluation.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A wrong statement found in an answer (a misconception), stored in {@code evaluations.factual_errors}.
 *
 * @param claim          what the candidate said
 * @param correction     what is true
 * @param sourceChunkIds knowledge chunks that back the correction
 * @param repeats        the claim of an earlier mistake of the same interview that this one repeats, as the interview
 *                       memory recorded it; set only when the evaluator linked the two by a token it was given, else null
 * @param criterion      the system design dimension the mistake concerns (it caps that dimension); null for other evaluators
 */
public record FactualError(
        String claim,
        String correction,
        List<UUID> sourceChunkIds,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String repeats,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Criterion criterion) {

    public static final int MAX_LENGTH = 1000;

    public FactualError {
        Require.notBlank(claim, "claim", MAX_LENGTH);
        Require.notBlank(correction, "correction", MAX_LENGTH);
        sourceChunkIds = Immutables.list(sourceChunkIds);
        repeats = Require.nullOrNotBlank(repeats, "repeats", MAX_LENGTH);
    }

    /** A mistake that names no design dimension. */
    public FactualError(String claim, String correction, List<UUID> sourceChunkIds, @Nullable String repeats) {
        this(claim, correction, sourceChunkIds, repeats, null);
    }

    /** A mistake not linked to an earlier one. */
    public FactualError(String claim, String correction, List<UUID> sourceChunkIds) {
        this(claim, correction, sourceChunkIds, null, null);
    }
}
