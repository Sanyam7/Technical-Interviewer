package com.techinterviewer.interview.port;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import org.jspecify.annotations.Nullable;

/**
 * What the system design evaluator knows besides the thread (AI_ARCHITECTURE.md section 2.12).
 *
 * @param architecture the latest architecture diagram of the thread, as text ({@code ArchitectureDiagram.describe()}); null when the
 *                     candidate drew none. It is the candidate's material: its words count as theirs for quotes.
 * @param probe        the dimension the latest question probes; null for the problem itself
 */
public record DesignContext(@Nullable String architecture, @Nullable Criterion probe) {

    public static final int MAX_ARCHITECTURE_LENGTH = 12_000;

    public DesignContext {
        architecture = Require.nullOrNotBlank(architecture, "architecture", MAX_ARCHITECTURE_LENGTH);
    }
}
