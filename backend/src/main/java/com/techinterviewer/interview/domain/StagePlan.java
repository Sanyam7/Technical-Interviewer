package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;

/**
 * How many main questions of the questioning section one stage asks (AI_ARCHITECTURE.md section 2.15).
 *
 * @param stage the stage
 * @param slots its main questions; follow-ups come on top, from the section's time
 */
public record StagePlan(InterviewStage stage, int slots) {

    public StagePlan {
        Require.nonNull(stage, "stage");
        Require.between(slots, 0, SectionPlan.MAX_SLOTS, "slots");
    }
}
