package com.techinterviewer.interview.domain;

import com.techinterviewer.common.validation.Require;

/**
 * The share of the questioning section's main questions each stage gets, in whole percent that add up to 100. A stage may get
 * nothing.
 *
 * @param fundamentals share of {@link InterviewStage#FUNDAMENTALS}
 * @param practical    share of {@link InterviewStage#PRACTICAL}
 * @param project      share of {@link InterviewStage#PROJECT}
 */
public record StageMix(int fundamentals, int practical, int project) {

    public static final int TOTAL = 100;

    public StageMix {
        Require.between(fundamentals, 0, TOTAL, "fundamentals");
        Require.between(practical, 0, TOTAL, "practical");
        Require.between(project, 0, TOTAL, "project");
        if (fundamentals + practical + project != TOTAL) {
            throw new IllegalArgumentException("the stage shares must add up to " + TOTAL + " but add up to "
                    + (fundamentals + practical + project));
        }
    }

    public int shareOf(InterviewStage stage) {
        return switch (stage) {
            case FUNDAMENTALS -> fundamentals;
            case PRACTICAL -> practical;
            case PROJECT -> project;
        };
    }
}
