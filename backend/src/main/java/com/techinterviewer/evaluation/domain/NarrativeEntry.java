package com.techinterviewer.evaluation.domain;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.UUID;

/**
 * A strength or an improvement area of the narrative with the evaluations that back it.
 *
 * @param text          the statement
 * @param evaluationIds at least one evaluation of the interview that shows it
 */
public record NarrativeEntry(String text, List<UUID> evaluationIds) {

    public static final int MAX_TEXT_LENGTH = 1000;

    public NarrativeEntry {
        Require.notBlank(text, "text", MAX_TEXT_LENGTH);
        evaluationIds = Immutables.list(evaluationIds);
        if (evaluationIds.isEmpty()) {
            throw new IllegalArgumentException("a narrative entry must cite at least one evaluation");
        }
    }
}
