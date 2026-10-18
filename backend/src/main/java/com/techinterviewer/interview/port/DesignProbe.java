package com.techinterviewer.interview.port;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import com.techinterviewer.evaluation.domain.Criterion;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a probe of a system design thread must ask about (AI_ARCHITECTURE.md section 2.12): the dimension the engine chose, the
 * scenario to build the question on, and the design as the candidate has described it so far.
 *
 * @param criterion    the dimension to probe
 * @param meaning      what the dimension covers, in words for the prompt
 * @param scenario     the realistic situation to probe it with (the problem's own, or the generic one); asked as it is when no
 *                     model can word the probe
 * @param problem      the design problem as the candidate read it
 * @param discussion   every earlier question and answer of the thread, the problem first, oldest first
 * @param architecture the latest diagram of the thread as text; null when the candidate drew none
 */
public record DesignProbe(
        Criterion criterion,
        String meaning,
        String scenario,
        String problem,
        List<ThreadTurn> discussion,
        @Nullable String architecture) {

    public DesignProbe {
        Require.nonNull(criterion, "criterion");
        Require.notBlank(meaning, "meaning", 300);
        Require.notBlank(scenario, "scenario", 400);
        Require.notBlank(problem, "problem", 10_000);
        discussion = Immutables.list(discussion);
        if (discussion.isEmpty()) {
            throw new IllegalArgumentException("a probe follows at least the answer to the problem");
        }
        architecture = Require.nullOrNotBlank(architecture, "architecture", DesignContext.MAX_ARCHITECTURE_LENGTH);
    }
}
