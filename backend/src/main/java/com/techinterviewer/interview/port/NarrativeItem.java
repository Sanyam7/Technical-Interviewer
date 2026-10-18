package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;
import java.util.UUID;

/**
 * A strength or an improvement area of the narrative. The evaluation ids are the evidence: the server verifies
 * that each exists in the interview and drops items that cite none.
 */
public record NarrativeItem(String text, List<UUID> evaluationIds) {

    public static final int MAX_LENGTH = 1000;

    public NarrativeItem {
        Require.notBlank(text, "text", MAX_LENGTH);
        evaluationIds = Immutables.list(evaluationIds);
    }
}
