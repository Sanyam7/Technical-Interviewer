package com.techinterviewer.interview.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How the questioning section divides its main questions between the stages (AI_ARCHITECTURE.md section 2.15). Each preset has a
 * fixed share per stage and a stage order; CUSTOM takes the shares the candidate chose and the order of FUNDAMENTALS_FIRST.
 */
public enum InterviewStrategy {

    /** The default: every selected technology's fundamentals first, then practical scenarios, then a little project discussion. */
    FUNDAMENTALS_FIRST(new StageMix(60, 25, 15), List.of(InterviewStage.FUNDAMENTALS, InterviewStage.PRACTICAL, InterviewStage.PROJECT)),
    /** Fundamentals, practical scenarios and project discussion in similar parts. */
    BALANCED(new StageMix(40, 30, 30), List.of(InterviewStage.FUNDAMENTALS, InterviewStage.PRACTICAL, InterviewStage.PROJECT)),
    /** A short fundamentals baseline, then mostly the candidate's own experience; needs a résumé. */
    RESUME_FOCUSED(new StageMix(20, 20, 60), List.of(InterviewStage.FUNDAMENTALS, InterviewStage.PROJECT, InterviewStage.PRACTICAL)),
    /** The shares the candidate chose, in the order of FUNDAMENTALS_FIRST. */
    CUSTOM(null, List.of(InterviewStage.FUNDAMENTALS, InterviewStage.PRACTICAL, InterviewStage.PROJECT));

    public static final InterviewStrategy DEFAULT = FUNDAMENTALS_FIRST;

    private final @Nullable StageMix preset;
    private final List<InterviewStage> order;

    InterviewStrategy(@Nullable StageMix preset, List<InterviewStage> order) {
        this.preset = preset;
        this.order = order;
    }

    /** The fixed mix of a preset; null for CUSTOM, whose mix the candidate chooses. */
    public @Nullable StageMix preset() {
        return preset;
    }

    /** The order the stages run in. */
    public List<InterviewStage> order() {
        return order;
    }

    /** True when the strategy needs a résumé to have something to discuss. */
    public boolean requiresResume() {
        return this == RESUME_FOCUSED;
    }
}
